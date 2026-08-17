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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;

// Middleware for distributed mutual exclusion backed by a RabbitMQ token queue.
public class DistributedCriticalSection implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DistributedCriticalSection.class);
    private static final byte[] EMPTY_BODY = new byte[0];

    // Test hook executed only during the bootstrap publish path.
    @FunctionalInterface
    interface BootstrapHook {
        void beforeTokenPublish() throws IOException, InterruptedException;
    }

    private final Connection connection;
    private final String csName;
    private final TokenQueueManager tokenCirculationQueueManager;
    private final BrokerBootstrapLock tokenBootstrapGuard;
    private final boolean ownConnection;
    private final BootstrapHook bootstrapHook;

    private Channel channel;
    private String consumerTag;
    private Long currentDeliveryTag;

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
        this.tokenCirculationQueueManager = new TokenQueueManager(this.csName);
        this.tokenBootstrapGuard = new BrokerBootstrapLock(this.csName);
        this.ownConnection = ownConnection;
        this.bootstrapHook = bootstrapHook == null ? () -> { } : bootstrapHook;
        this.channel = this.connection.createChannel();
        this.channel.basicQos(1);
        initializeToken();
    }

    // Seed the shared token only when the critical section is still uninitialized.
    private void initializeToken() throws IOException, InterruptedException {
        synchronized (this) {
            // Ensure the token queue exists before inspecting or populating it.
            this.channel = tokenCirculationQueueManager.declareQueue(this.channel);

            // Serialize bootstrap so two processes cannot create the first token concurrently.
            tokenBootstrapGuard.withLock(connection, tokenBootstrapGuardChannel -> {
                String tokenCirculationQueueName = tokenCirculationQueueManager.tokenCirculationQueueName();

                // Read the current queue state without changing broker data.
                AMQP.Queue.DeclareOk tokenCirculationQueueState =
                        tokenBootstrapGuardChannel.queueDeclarePassive(tokenCirculationQueueName);

                // Bootstrap only when no token is queued and no consumer is registered.
                if (tokenCirculationQueueState.getMessageCount() == 0
                        && tokenCirculationQueueState.getConsumerCount() == 0) {
                    // Require broker confirmation before considering the first token created.
                    tokenBootstrapGuardChannel.confirmSelect();

                    // Run the optional test hook just before the first publish.
                    bootstrapHook.beforeTokenPublish();

                    // Publish one persistent empty message as the initial token.
                    tokenBootstrapGuardChannel.basicPublish(
                            "",
                            tokenCirculationQueueName,
                            MessageProperties.PERSISTENT_TEXT_PLAIN,
                            EMPTY_BODY);
                    try {
                        // Wait for broker confirmation so a failed bootstrap can be retried safely.
                        if (!tokenBootstrapGuardChannel.waitForConfirms(2000)) {
                            throw new IOException("Failed to confirm token bootstrap for critical section '" + csName + "'");
                        }
                    } catch (TimeoutException e) {
                        throw new IOException("Timed out waiting for token bootstrap confirm for critical section '" + csName + "'", e);
                    }

                    // The shared critical section now has its initial token.
                    logger.info("Initialized critical section token for '{}'", csName);
                } else {
                    // Skip bootstrap because the token already exists or is already owned.
                    logger.debug(
                            "Critical section token for '{}' already initialized (messages={}, consumers={})",
                            csName,
                            tokenCirculationQueueState.getMessageCount(),
                            tokenCirculationQueueState.getConsumerCount());
                }
            });
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
        if (currentDeliveryTag != null) {
            throw new IllegalStateException("Process is already in critical section '" + csName + "'");
        }

        logger.debug("Attempting to enter critical section '{}'", csName);

        BlockingQueue<Delivery> deliveryQueue = new ArrayBlockingQueue<>(1);
        DeliverCallback deliverCallback = (tag, delivery) -> {
            // At most one token delivery is expected for each acquisition attempt.
            if (!deliveryQueue.offer(delivery)) {
                logger.warn("Delivery queue capacity exceeded; token message dropped for '{}'", csName);
            }
        };

        String tokenCirculationQueueName = tokenCirculationQueueManager.tokenCirculationQueueName();
        // Keep the consumer registered while holding the token so bootstrap can observe ownership.
        consumerTag = channel.basicConsume(tokenCirculationQueueName, false, deliverCallback, tag -> { });

        boolean acquired = false;
        try {
            // Wait until RabbitMQ delivers the token to this process.
            Delivery delivery = deliveryQueue.take();
            currentDeliveryTag = delivery.getEnvelope().getDeliveryTag();
            acquired = true;
            logger.debug("Successfully entered critical section '{}'", csName);
        } catch (InterruptedException e) {
            // If interrupted after delivery, immediately requeue the token.
            Delivery delivery = deliveryQueue.poll();
            if (delivery != null) {
                try {
                    channel.basicReject(delivery.getEnvelope().getDeliveryTag(), true);
                } catch (IOException ioex) {
                    logger.error("Failed to reject token on interrupt", ioex);
                }
            }
            throw e;
        } finally {
            if (!acquired) {
                cancelConsumerQuietly();
            }
        }
    }

    // Release the token and make the critical section available again.
    public synchronized void exit() throws IOException, InterruptedException {
        if (currentDeliveryTag == null) {
            throw new IllegalStateException("Process is not in critical section '" + csName + "'");
        }

        logger.debug("Exiting critical section '{}'", csName);

        tokenBootstrapGuard.withLock(connection, tokenBootstrapGuardChannel -> {
            // Stop appearing as an active owner before returning the token to the queue.
            cancelConsumerQuietly();
            // Requeue the unacknowledged delivery instead of publishing a new token.
            channel.basicNack(currentDeliveryTag, false, true);
        });

        currentDeliveryTag = null;
        logger.debug("Successfully exited critical section '{}'", csName);
    }

    // Cancel the local consumer without turning cleanup problems into API failures.
    private void cancelConsumerQuietly() {
        if (consumerTag == null) {
            return;
        }
        try {
            if (channel != null && channel.isOpen()) {
                channel.basicCancel(consumerTag);
            }
        } catch (IOException e) {
            logger.error("Failed to cancel consumer for '{}'", csName, e);
        } finally {
            consumerTag = null;
        }
    }

    // Close local broker resources; RabbitMQ requeues any unacknowledged token on channel shutdown.
    @Override
    public void close() {
        cancelConsumerQuietly();

        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
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
