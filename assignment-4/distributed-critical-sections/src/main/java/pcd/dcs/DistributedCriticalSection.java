package pcd.dcs;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.MessageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

// Middleware for distributed mutual exclusion backed by a RabbitMQ token queue.
public class DistributedCriticalSection implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DistributedCriticalSection.class);
    private static final byte[] EMPTY_BODY = new byte[0];
    private static final int TOKEN_BOOTSTRAP_CONFIRM_TIMEOUT_MILLIS = 2000;

    // Test hook executed only during the bootstrap publish path.
    @FunctionalInterface
    interface BootstrapHook {
        void beforeTokenPublish() throws IOException, InterruptedException;
    }

    private final Connection connection;
    private final String csName;
    private final TokenQueueManager criticalSectionTokenQueueManager;
    private final BrokerBootstrapLock tokenCreationGuard;
    private final boolean ownConnection;
    private final BootstrapHook bootstrapHook;

    private Channel criticalSectionTokenChannel;
    private String criticalSectionTokenConsumerTag;
    private Long currentCriticalSectionTokenDeliveryTag;

    // Reuse an existing broker connection owned by the caller.
    public DistributedCriticalSection(Connection connection, String csName) throws IOException, InterruptedException {
        this(connection, csName, () -> { });
    }

    // Internal constructor variant used by tests.
    DistributedCriticalSection(Connection connection, String csName, BootstrapHook bootstrapHook)
            throws IOException, InterruptedException {
        this(connection, csName, false, bootstrapHook);
    }

    // Open and own a dedicated broker connection.
    public DistributedCriticalSection(String host, int port, String csName)
            throws IOException, TimeoutException, InterruptedException {
        this(host, port, csName, () -> { });
    }

    // Internal constructor variant used by tests.
    DistributedCriticalSection(String host, int port, String csName, BootstrapHook bootstrapHook)
            throws IOException, TimeoutException, InterruptedException {
        this(openConnection(host, port), csName, true, bootstrapHook);
    }

    // Build the runtime state shared by all constructor variants.
    private DistributedCriticalSection(
            Connection connection,
            String csName,
            boolean ownConnection,
            BootstrapHook bootstrapHook
    ) throws IOException, InterruptedException {
        this.connection = requireConnection(connection);
        this.csName = requireCriticalSectionName(csName);
        this.criticalSectionTokenQueueManager = new TokenQueueManager(this.csName);
        this.tokenCreationGuard = new BrokerBootstrapLock(this.csName);
        this.ownConnection = ownConnection;
        this.bootstrapHook = bootstrapHook == null ? () -> { } : bootstrapHook;
        this.criticalSectionTokenChannel = this.connection.createChannel();
        this.criticalSectionTokenChannel.basicQos(1);
        initializeToken();
    }

    // Seed the shared token only when the critical section is still uninitialized.
    private void initializeToken() throws IOException, InterruptedException {
        synchronized (this) {
            // Ensure the token queue exists before inspecting or populating it.
            this.criticalSectionTokenChannel =
                    criticalSectionTokenQueueManager.declareQueue(this.criticalSectionTokenChannel);

            // Serialize bootstrap so two processes cannot create the first token concurrently.
            tokenCreationGuard.withLock(connection, tokenCreationGuardChannel -> {
                String criticalSectionTokenQueueName = criticalSectionTokenQueueManager.criticalSectionTokenQueue();

                // Read the current queue state without changing broker data.
                AMQP.Queue.DeclareOk criticalSectionTokenQueueState =
                        tokenCreationGuardChannel.queueDeclarePassive(criticalSectionTokenQueueName);

                // Bootstrap only when no token is queued and no consumer is registered.
                if (criticalSectionTokenQueueState.getMessageCount() == 0
                        && criticalSectionTokenQueueState.getConsumerCount() == 0) {
                    publishInitialToken(tokenCreationGuardChannel, criticalSectionTokenQueueName);
                    logger.info("Initialized critical section token for '{}'", csName);
                } else {
                    // Skip bootstrap because the token already exists or is already owned.
                    logger.debug(
                            "Critical section token for '{}' already initialized (messages={}, consumers={})",
                            csName,
                            criticalSectionTokenQueueState.getMessageCount(),
                            criticalSectionTokenQueueState.getConsumerCount());
                }
            });
        }
    }

    // Publish the first token only after RabbitMQ confirms the broker accepted it.
    private void publishInitialToken(Channel tokenCreationGuardChannel, String criticalSectionTokenQueueName)
            throws IOException, InterruptedException {
        tokenCreationGuardChannel.confirmSelect();
        bootstrapHook.beforeTokenPublish();
        tokenCreationGuardChannel.basicPublish(
                "",
                criticalSectionTokenQueueName,
                MessageProperties.PERSISTENT_TEXT_PLAIN,
                EMPTY_BODY);
        try {
            if (!tokenCreationGuardChannel.waitForConfirms(TOKEN_BOOTSTRAP_CONFIRM_TIMEOUT_MILLIS)) {
                throw new IOException("Failed to confirm token bootstrap for critical section '" + csName + "'");
            }
        } catch (TimeoutException e) {
            throw new IOException("Timed out waiting for token bootstrap confirm for critical section '" + csName + "'", e);
        }
    }

    // Open a fresh connection when this instance owns the broker lifecycle.
    private static Connection openConnection(String host, int port) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        return factory.newConnection();
    }

    // Reject null connections early to keep failure local to construction.
    private static Connection requireConnection(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Connection cannot be null");
        }
        return connection;
    }

    // Preserve the original critical-section name but reject empty identifiers.
    private static String requireCriticalSectionName(String csName) {
        if (csName == null || csName.trim().isEmpty()) {
            throw new IllegalArgumentException("Critical section name cannot be empty");
        }
        return csName;
    }

    // Block until this instance consumes the shared token.
    public synchronized void enter() throws IOException, InterruptedException {
        if (currentCriticalSectionTokenDeliveryTag != null) {
            throw new IllegalStateException("Process is already in critical section '" + csName + "'");
        }

        logger.debug("Attempting to enter critical section '{}'", csName);

        CompletableFuture<Delivery> criticalSectionTokenDeliveryFuture = new CompletableFuture<>();
        DeliverCallback criticalSectionTokenDeliveryCallback =
                (tag, delivery) -> criticalSectionTokenDeliveryFuture.complete(delivery);

        String criticalSectionTokenQueueName = criticalSectionTokenQueueManager.criticalSectionTokenQueue();
        // Keep the consumer registered while holding the token so bootstrap can observe ownership.
        criticalSectionTokenConsumerTag = criticalSectionTokenChannel.basicConsume(
                criticalSectionTokenQueueName,
                false,
                criticalSectionTokenDeliveryCallback,
                tag -> { });

        boolean acquired = false;
        try {
            // Wait until RabbitMQ delivers the token to this process.
            Delivery criticalSectionTokenDelivery = waitForTokenDelivery(criticalSectionTokenDeliveryFuture);
            currentCriticalSectionTokenDeliveryTag = criticalSectionTokenDelivery.getEnvelope().getDeliveryTag();
            acquired = true;
            logger.debug("Successfully entered critical section '{}'", csName);
        } catch (InterruptedException e) {
            // If interrupted after delivery, immediately requeue the token.
            if (criticalSectionTokenDeliveryFuture.isDone()
                    && !criticalSectionTokenDeliveryFuture.isCompletedExceptionally()) {
                rejectDeliveredTokenQuietly(criticalSectionTokenDeliveryFuture);
            }
            throw e;
        } finally {
            if (!acquired) {
                cancelCriticalSectionTokenConsumerQuietly();
            }
        }
    }

    // Wait synchronously for the asynchronous RabbitMQ delivery callback.
    private Delivery waitForTokenDelivery(CompletableFuture<Delivery> criticalSectionTokenDeliveryFuture)
            throws InterruptedException, IOException {
        try {
            return criticalSectionTokenDeliveryFuture.get();
        } catch (ExecutionException e) {
            throw new IOException("Failed while waiting for the token delivery", e.getCause());
        }
    }

    // Requeue the delivered token if interruption happens after RabbitMQ already sent it.
    private void rejectDeliveredTokenQuietly(CompletableFuture<Delivery> criticalSectionTokenDeliveryFuture) {
        Delivery criticalSectionTokenDelivery = criticalSectionTokenDeliveryFuture.getNow(null);
        if (criticalSectionTokenDelivery == null) {
            return;
        }
        try {
            criticalSectionTokenChannel.basicReject(criticalSectionTokenDelivery.getEnvelope().getDeliveryTag(), true);
        } catch (IOException e) {
            logger.error("Failed to reject token on interrupt", e);
        }
    }

    // Release the token and make the critical section available again.
    public synchronized void exit() throws IOException, InterruptedException {
        if (currentCriticalSectionTokenDeliveryTag == null) {
            throw new IllegalStateException("Process is not in critical section '" + csName + "'");
        }

        logger.debug("Exiting critical section '{}'", csName);

        tokenCreationGuard.withLock(connection, tokenCreationGuardChannel -> {
            // Stop appearing as an active owner before returning the token to the queue.
            cancelCriticalSectionTokenConsumerQuietly();
            // Requeue the unacknowledged delivery instead of publishing a new token.
            criticalSectionTokenChannel.basicNack(currentCriticalSectionTokenDeliveryTag, false, true);
        });

        currentCriticalSectionTokenDeliveryTag = null;
        logger.debug("Successfully exited critical section '{}'", csName);
    }

    // Cancel the local consumer without turning cleanup problems into API failures.
    private void cancelCriticalSectionTokenConsumerQuietly() {
        if (criticalSectionTokenConsumerTag == null) {
            return;
        }
        try {
            if (criticalSectionTokenChannel != null && criticalSectionTokenChannel.isOpen()) {
                criticalSectionTokenChannel.basicCancel(criticalSectionTokenConsumerTag);
            }
        } catch (IOException e) {
            logger.error("Failed to cancel consumer for '{}'", csName, e);
        } finally {
            criticalSectionTokenConsumerTag = null;
        }
    }

    // Close local broker resources; RabbitMQ requeues any unacknowledged token on channel shutdown.
    @Override
    public void close() {
        cancelCriticalSectionTokenConsumerQuietly();

        if (criticalSectionTokenChannel != null && criticalSectionTokenChannel.isOpen()) {
            try {
                criticalSectionTokenChannel.close();
            } catch (IOException | TimeoutException e) {
                logger.error("Failed to close channel", e);
            }
        }
        if (ownConnection && connection != null && connection.isOpen()) {
            try {
                connection.close();
            } catch (IOException e) {
                logger.error("Failed to close connection", e);
            }
        }
    }
}
