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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
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
 * that no token message exists and no consumer is holding one. Because the lock queue is deleted at
 * the end of the transition, no bootstrap artifact can outlive the token.
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
    private static final String TOKEN_QUEUE_PREFIX = "cs_token_";
    private static final String BOOTSTRAP_LOCK_QUEUE_PREFIX = "cs_bootstrap_lock_";
    private static final long BOOTSTRAP_LOCK_RETRY_DELAY_MILLIS = 50L;
    private static final long BOOTSTRAP_LOCK_TIMEOUT_MILLIS = 10_000L;
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
    private Channel channel;
    private final String csName;
    private final String queueName;
    private final String bootstrapLockQueue;
    private final boolean ownConnection;
    private final BootstrapHook bootstrapHook;

    private String consumerTag;
    private Long currentDeliveryTag;

    /**
     * Creates a new distributed critical section instance using an existing RabbitMQ connection.
     * The connection lifecycle will NOT be managed by this instance (it will not be closed on
     * {@link #close()}).
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
        this.queueName = TOKEN_QUEUE_PREFIX + csName;
        this.bootstrapLockQueue = BOOTSTRAP_LOCK_QUEUE_PREFIX + csName;
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
        this.queueName = TOKEN_QUEUE_PREFIX + csName;
        this.bootstrapLockQueue = BOOTSTRAP_LOCK_QUEUE_PREFIX + csName;
        this.ownConnection = true;
        this.bootstrapHook = bootstrapHook == null ? () -> { } : bootstrapHook;
        this.channel = connection.createChannel();
        this.channel.basicQos(1);
        initializeToken();
    }

    /**
     * Seeds the token queue exactly once, using a temporary broker lock to serialize bootstrap.
     * <p>
     * The lock queue is exclusive to the connection that is currently deciding whether a token must
     * be created. The token is published only if a passive inspection shows that the queue has no
     * messages and no active consumer. That combination means the critical section has never been
     * initialized yet. The lock queue is deleted in a {@code finally} block, so no bootstrap
     * artifact can survive the token itself.
     * </p>
     */
    private void initializeToken() throws IOException, InterruptedException {
        synchronized (connection) {
            declareTokenQueueWithRecovery();

            withBootstrapLock(lockChannel -> {
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
     * Declares the token queue on the RabbitMQ broker.
     * <p>
     * If the queue already exists on the broker with inequivalent arguments (e.g., from an older
     * version of the middleware or an external producer), RabbitMQ closes the channel with a
     * {@code 406 PRECONDITION_FAILED} error. This method catches that condition and initiates
     * automatic queue recreation.
     * </p>
     *
     * @throws IOException if queue declaration fails for reasons other than argument mismatch
     */
    private void declareTokenQueueWithRecovery() throws IOException {
        try {
            channel.queueDeclare(queueName, true, false, false, tokenQueueArguments());
        } catch (IOException e) {
            if (isPreconditionFailed(e)) {
                logger.warn("Queue '{}' on broker has inequivalent arguments. Re-creating queue...", queueName);
                recreateTokenQueue();
            } else {
                throw e;
            }
        }
    }

    /**
     * Re-creates the token queue after an argument mismatch error.
     * <p>
     * Opens a temporary repair channel to delete the stale queue, re-opens the main AMQP channel,
     * and re-declares the queue with the expected middleware arguments.
     * </p>
     *
     * @throws IOException if channel re-opening or queue declaration fails
     */
    private void recreateTokenQueue() throws IOException {
        try (Channel repairChannel = connection.createChannel()) {
            repairChannel.queueDelete(queueName);
        } catch (TimeoutException e) {
            throw new IOException("Failed to close repair channel during queue recreation", e);
        }
        reopenChannel();
        channel.queueDeclare(queueName, true, false, false, tokenQueueArguments());
    }

    /**
     * Re-opens the main AMQP channel associated with this instance and resets QoS settings.
     * <p>
     * This is required because AMQP 0-9-1 channel errors (such as 406 PRECONDITION_FAILED)
     * cause the broker to close the active channel.
     * </p>
     *
     * @throws IOException if opening a new channel fails
     */
    private void reopenChannel() throws IOException {
        if (channel != null && channel.isOpen()) {
            try {
                channel.close();
            } catch (IOException | TimeoutException ignored) { }
        }
        channel = connection.createChannel();
        channel.basicQos(1);
    }

    /**
     * Returns the queue arguments used when declaring the token queue.
     *
     * @return {@code null} to request standard durable queue configuration without extra x-arguments
     */
    private Map<String, Object> tokenQueueArguments() {
        return null;
    }

    /**
     * Inspects an exception hierarchy to determine whether it was caused by a RabbitMQ
     * {@code 406 PRECONDITION_FAILED} channel shutdown.
     *
     * @param e the root IOException caught during channel operations
     * @return {@code true} if the exception chain contains a 406 PRECONDITION_FAILED error
     */
    private static boolean isPreconditionFailed(IOException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof com.rabbitmq.client.ShutdownSignalException sse) {
                if (sse.getReason() instanceof AMQP.Channel.Close close) {
                    if (close.getReplyCode() == 406) {
                        return true;
                    }
                }
                if (sse.getMessage() != null && (sse.getMessage().contains("PRECONDITION_FAILED") || sse.getMessage().contains("inequivalent arg"))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Executes an action while holding a temporary broker-side bootstrap lock.
     * <p>
     * The lock is materialized as an exclusive, auto-deleted queue {@code cs_bootstrap_lock_<csName>}.
     * Concurrent instances attempt to declare this queue. Exactly one instance succeeds at a time;
     * others receive an {@link IOException} and retry with exponential/polling delay until the deadline
     * is reached. The lock queue is deleted in a {@code finally} block upon completion.
     * </p>
     *
     * @param action the callback to execute while holding the broker lock
     * @throws IOException          if lock acquisition or action execution fails
     * @throws InterruptedException if thread sleep during lock polling is interrupted
     */
    private void withBootstrapLock(BootstrapAction action) throws IOException, InterruptedException {
        synchronized (connection) {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BOOTSTRAP_LOCK_TIMEOUT_MILLIS);

            while (true) {
                Channel lockChannel = connection.createChannel();
                try {
                    try {
                        lockChannel.queueDeclare(bootstrapLockQueue, false, true, false, null);
                    } catch (IOException lockFailure) {
                        if (System.nanoTime() >= deadlineNanos) {
                            throw new IOException(
                                    "Timed out acquiring bootstrap lock for critical section '" + csName + "'",
                                    lockFailure);
                        }
                        Thread.sleep(BOOTSTRAP_LOCK_RETRY_DELAY_MILLIS);
                        continue;
                    }

                    try {
                        action.run(lockChannel);
                    } finally {
                        try {
                            lockChannel.queueDelete(bootstrapLockQueue);
                        } catch (IOException cleanupFailure) {
                            logger.warn("Failed to delete bootstrap lock queue for '{}'", csName, cleanupFailure);
                        }
                    }
                    return;
                } finally {
                    try {
                        if (lockChannel.isOpen()) {
                            lockChannel.close();
                        }
                    } catch (IOException | TimeoutException cleanupFailure) {
                        logger.warn("Failed to close bootstrap lock channel for '{}'", csName, cleanupFailure);
                    }
                }
            }
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
        DeliverCallback deliverCallback = (tag, delivery) -> deliveryQueue.offer(delivery);

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
     * requeued, which prevents a concurrent bootstrapper from observing an intermediate state.
     * The same delivery tag is requeued on the broker and remains owned by this instance until the
     * release succeeds.
     * </p>
     *
     * @throws IOException          if a communication error occurs
     * @throws IllegalStateException if the process is not currently in the critical section
     */
    public synchronized void exit() throws IOException, InterruptedException {
        if (currentDeliveryTag == null) {
            throw new IllegalStateException("Process is not in critical section '" + csName + "'");
        }

        logger.debug("Exiting critical section '{}'", csName);

        withBootstrapLock(lockChannel -> {
            cancelConsumerQuietly();
            channel.basicNack(currentDeliveryTag, false, true);
        });

        currentDeliveryTag = null;
        logger.debug("Successfully exited critical section '{}'", csName);
    }

    /**
     * Cancels the active RabbitMQ consumer quietly without propagating channel errors.
     * <p>
     * Resets {@code consumerTag} to {@code null} regardless of whether cancellation succeeds.
     * </p>
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
     * If the critical section is currently held, the unacknowledged token is requeued by RabbitMQ
     * when the channel or connection closes.
     *
     * @throws Exception if an error occurs while closing RabbitMQ resources
     */
    @Override
    public void close() throws Exception {
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

    /**
     * Functional callback interface executed while holding the temporary bootstrap broker lock.
     */
    @FunctionalInterface
    private interface BootstrapAction {
        /**
         * Executes an operation using the channel that holds the bootstrap lock.
         *
         * @param lockChannel the AMQP channel holding the exclusive lock queue
         * @throws IOException          if an AMQP operation fails
         * @throws InterruptedException if thread execution is interrupted
         */
        void run(Channel lockChannel) throws IOException, InterruptedException;
    }
}
