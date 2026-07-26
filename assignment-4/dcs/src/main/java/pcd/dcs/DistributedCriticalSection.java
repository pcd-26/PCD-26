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

/**
 * A high-level middleware providing support for realizing distributed critical sections
 * (distributed mutual exclusion) among processes running in a distributed system.
 * <p>
 * This implementation uses RabbitMQ as a Message-Oriented Middleware (MOM).
 * Mutual exclusion is achieved by using a single persistent token message stored in a dedicated
 * RabbitMQ queue for the critical section. Only the process that successfully retrieves the token
 * message can enter the critical section.
 * </p>
 * <p>
 * The bootstrap protocol is crash-safe: a temporary exclusive queue on the broker serializes the
 * decision to seed the token, and the token is published only after a passive queue inspection shows
 * that no token message exists and no consumer is holding one.
 * </p>
 * <p>
 * The token is consumed with manual acknowledgment ({@code autoAck = false}). While a process holds
 * the critical section, its consumer stays registered so a passive bootstrap check can distinguish an
 * initialized system from a non-initialized one. If the process crashes or its connection to RabbitMQ
 * is closed, RabbitMQ automatically re-queues the unacknowledged token message.
 * </p>
 */
public class DistributedCriticalSection implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DistributedCriticalSection.class);
    private static final byte[] EMPTY_BODY = new byte[0];

    /**
     * Test seam that runs only while the bootstrap lock is held and before the first token publish.
     * Production code uses the no-op default.
     */
    @FunctionalInterface
    interface BootstrapHook {
        void beforeTokenPublish() throws IOException, InterruptedException;
    }

    private final Connection connection;
    private final String csName;
    private final TokenQueueManager tokenQueueManager;
    private final BrokerBootstrapLock bootstrapLock;
    private final boolean ownConnection;
    private final BootstrapHook bootstrapHook;

    private Channel channel;
    private String consumerTag;
    private Long currentDeliveryTag;

    /**
     * Creates a new distributed critical section instance using an existing RabbitMQ connection.
     * The connection lifecycle will NOT be managed by this instance (it will not be closed on {@link #close()}).
     *
     * @param connection the active RabbitMQ connection to use
     * @param csName     the name of the critical section
     * @throws IOException          if a channel cannot be opened or initialization fails
     * @throws InterruptedException if bootstrap lock acquisition is interrupted
     */
    public DistributedCriticalSection(Connection connection, String csName) throws IOException, InterruptedException {
        this(connection, csName, () -> { });
    }

    DistributedCriticalSection(Connection connection, String csName, BootstrapHook bootstrapHook)
            throws IOException, InterruptedException {
        if (connection == null) {
            throw new IllegalArgumentException("Connection cannot be null");
        }
        if (csName == null || csName.trim().isEmpty()) {
            throw new IllegalArgumentException("Critical section name cannot be empty");
        }
        this.connection = connection;
        this.csName = csName;
        this.tokenQueueManager = new TokenQueueManager(csName);
        this.bootstrapLock = new BrokerBootstrapLock(csName);
        this.ownConnection = false;
        this.bootstrapHook = bootstrapHook == null ? () -> { } : bootstrapHook;
        this.channel = connection.createChannel();
        this.channel.basicQos(1);
        initializeToken();
    }

    /**
     * Creates a new distributed critical section instance by establishing a new connection to RabbitMQ.
     * The connection will be closed when this instance is closed.
     *
     * @param host   the RabbitMQ broker host
     * @param port   the RabbitMQ broker port
     * @param csName the name of the critical section
     * @throws IOException          if a connection/channel cannot be opened or initialization fails
     * @throws TimeoutException     if connection establishment times out
     * @throws InterruptedException if bootstrap lock acquisition is interrupted
     */
    public DistributedCriticalSection(String host, int port, String csName)
            throws IOException, TimeoutException, InterruptedException {
        this(host, port, csName, () -> { });
    }

    DistributedCriticalSection(String host, int port, String csName, BootstrapHook bootstrapHook)
            throws IOException, TimeoutException, InterruptedException {
        if (csName == null || csName.trim().isEmpty()) {
            throw new IllegalArgumentException("Critical section name cannot be empty");
        }
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        this.connection = factory.newConnection();
        this.csName = csName;
        this.tokenQueueManager = new TokenQueueManager(csName);
        this.bootstrapLock = new BrokerBootstrapLock(csName);
        this.ownConnection = true;
        this.bootstrapHook = bootstrapHook == null ? () -> { } : bootstrapHook;
        this.channel = connection.createChannel();
        this.channel.basicQos(1);
        initializeToken();
    }

    /**
     * Seeds the token queue exactly once, using a temporary broker lock to serialize bootstrap.
     */
    private void initializeToken() throws IOException, InterruptedException {
        synchronized (this) {
            this.channel = tokenQueueManager.declareQueueWithRecovery(connection, this.channel);

            bootstrapLock.withLock(connection, lockChannel -> {
                String queueName = tokenQueueManager.queueName();
                AMQP.Queue.DeclareOk state = lockChannel.queueDeclarePassive(queueName);
                if (state.getMessageCount() == 0 && state.getConsumerCount() == 0) {
                    lockChannel.confirmSelect();
                    bootstrapHook.beforeTokenPublish();
                    lockChannel.basicPublish("", queueName, MessageProperties.PERSISTENT_TEXT_PLAIN, EMPTY_BODY);
                    try {
                        if (!lockChannel.waitForConfirms(2000)) {
                            throw new IOException("Failed to confirm token bootstrap for critical section '" + csName + "'");
                        }
                    } catch (TimeoutException e) {
                        throw new IOException("Timed out waiting for token bootstrap confirm for critical section '" + csName + "'", e);
                    }
                    logger.info("Initialized critical section token for '{}'", csName);
                } else {
                    logger.debug(
                            "Critical section token for '{}' already initialized (messages={}, consumers={})",
                            csName,
                            state.getMessageCount(),
                            state.getConsumerCount());
                }
            });
        }
    }

    /**
     * Enters the critical section. This method blocks until the lock is acquired.
     * <p>
     * Acquisition is achieved by consuming the token message from the queue. The consumer remains
     * registered while the critical section is held so the bootstrap logic can observe that the token
     * already exists. The delivery is intentionally left unacknowledged until {@link #exit()}.
     * </p>
     *
     * @throws IOException          if a communication error occurs
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws IllegalStateException if the current instance is already in the critical section
     */
    public synchronized void enter() throws IOException, InterruptedException {
        if (currentDeliveryTag != null) {
            throw new IllegalStateException("Process is already in critical section '" + csName + "'");
        }

        logger.debug("Attempting to enter critical section '{}'", csName);

        BlockingQueue<Delivery> deliveryQueue = new ArrayBlockingQueue<>(1);
        DeliverCallback deliverCallback = (tag, delivery) -> {
            if (!deliveryQueue.offer(delivery)) {
                logger.warn("Delivery queue capacity exceeded; token message dropped for '{}'", csName);
            }
        };

        String queueName = tokenQueueManager.queueName();
        consumerTag = channel.basicConsume(queueName, false, deliverCallback, tag -> { });

        boolean acquired = false;
        try {
            Delivery delivery = deliveryQueue.take();
            currentDeliveryTag = delivery.getEnvelope().getDeliveryTag();
            acquired = true;
            logger.debug("Successfully entered critical section '{}'", csName);
        } catch (InterruptedException e) {
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

    /**
     * Exits the critical section. This method releases the lock.
     * <p>
     * The temporary bootstrap lock is held while the local consumer is canceled and the token is
     * re-queued, which prevents a concurrent bootstrapper from observing an intermediate state.
     * The same delivery tag is re-queued on the broker and remains owned by this instance until the
     * release succeeds.
     * </p>
     *
     * @throws IOException          if a communication error occurs
     * @throws InterruptedException if thread execution is interrupted during release transition
     * @throws IllegalStateException if the process is not currently in the critical section
     */
    public synchronized void exit() throws IOException, InterruptedException {
        if (currentDeliveryTag == null) {
            throw new IllegalStateException("Process is not in critical section '" + csName + "'");
        }

        logger.debug("Exiting critical section '{}'", csName);

        bootstrapLock.withLock(connection, lockChannel -> {
            cancelConsumerQuietly();
            channel.basicNack(currentDeliveryTag, false, true);
        });

        currentDeliveryTag = null;
        logger.debug("Successfully exited critical section '{}'", csName);
    }

    /**
     * Cancels the active RabbitMQ consumer quietly without propagating channel errors.
     */
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

    /**
     * Closes the channels and optionally the connection if it was created by this instance.
     * If the critical section is currently held, the unacknowledged token is re-queued by RabbitMQ
     * when the channel or connection closes.
     */
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
