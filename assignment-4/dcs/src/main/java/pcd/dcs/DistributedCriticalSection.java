package pcd.dcs;

import com.rabbitmq.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;

/**
 * A high-level middleware providing support for realizing distributed critical sections
 * (distributed mutual exclusion) among processes running in a distributed system.
 * <p>
 * This implementation uses RabbitMQ as a Message-Oriented Middleware (MOM).
 * Mutual exclusion is achieved by using a single token message stored in a dedicated RabbitMQ queue
 * for the critical section. Only the process that successfully retrieves (consumes) the token
 * message can enter the critical section.
 * </p>
 * <p>
 * To ensure fault tolerance (e.g., if a process crashes while holding the lock), the token
 * message is consumed with manual acknowledgment ({@code autoAck = false}). If a process crashes
 * or its connection to RabbitMQ is closed, RabbitMQ automatically requeues the unacknowledged
 * token message, allowing other waiting processes to acquire it.
 * </p>
 * <p>
 * To prevent multiple token initialization races, a helper initialization flag queue with a maximum
 * length of 1 and {@code reject-publish} overflow policy is used. The first process to successfully
 * publish a marker to this queue is designated as the initializer and publishes the token. Subsequent
 * processes' publishes will be rejected (nacked), preventing duplicate tokens.
 * </p>
 */
public class DistributedCriticalSection implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DistributedCriticalSection.class);

    private final Connection connection;
    private final Channel channel;
    private final String csName;
    private final String queueName;
    private final String initFlagQueue;
    private final boolean ownConnection;

    private String consumerTag;
    private Long currentDeliveryTag;

    /**
     * Creates a new distributed critical section instance using an existing RabbitMQ connection.
     * The connection lifecycle will NOT be managed by this instance (it will not be closed on {@link #close()}).
     *
     * @param connection the active RabbitMQ connection to use
     * @param csName     the name of the critical section
     * @throws IOException if a channel cannot be opened or initialization fails
     */
    public DistributedCriticalSection(Connection connection, String csName) throws IOException {
        if (connection == null) {
            throw new IllegalArgumentException("Connection cannot be null");
        }
        if (csName == null || csName.trim().isEmpty()) {
            throw new IllegalArgumentException("Critical section name cannot be empty");
        }
        this.connection = connection;
        this.csName = csName;
        this.queueName = "cs_token_" + csName;
        this.initFlagQueue = "cs_init_flag_" + csName;
        this.ownConnection = false;
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
     * @throws IOException      if a connection/channel cannot be opened or initialization fails
     * @throws TimeoutException if connection establishment times out
     */
    public DistributedCriticalSection(String host, int port, String csName) throws IOException, TimeoutException {
        if (csName == null || csName.trim().isEmpty()) {
            throw new IllegalArgumentException("Critical section name cannot be empty");
        }
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        this.connection = factory.newConnection();
        this.csName = csName;
        this.queueName = "cs_token_" + csName;
        this.initFlagQueue = "cs_init_flag_" + csName;
        this.ownConnection = true;
        this.channel = connection.createChannel();
        this.channel.basicQos(1);
        initializeToken();
    }

    /**
     * Initializes the token and initialization queues.
     * <p>
     * Utilizes RabbitMQ publisher confirms and queue max-length/overflow settings to safely
     * seed exactly one token in a distributed, race-free manner.
     * </p>
     */
    private void initializeToken() throws IOException {
        // Declare the token queue: durable=true, exclusive=false, autoDelete=false
        channel.queueDeclare(queueName, true, false, false, null);

        // Declare the init flag queue: durable=true, exclusive=false, autoDelete=false
        // Arguments: max-length=1, overflow=reject-publish
        Map<String, Object> args = new HashMap<>();
        args.put("x-max-length", 1);
        args.put("x-overflow", "reject-publish");
        channel.queueDeclare(initFlagQueue, true, false, false, args);

        // We use a separate channel to avoid interfering with the main channel's state
        try (Channel initChannel = connection.createChannel()) {
            initChannel.confirmSelect();

            // Try to publish a unique marker message to the init flag queue
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                    .deliveryMode(2) // persistent
                    .messageId("init-marker-" + csName)
                    .build();

            initChannel.basicPublish("", initFlagQueue, props, new byte[0]);

            try {
                if (initChannel.waitForConfirms(2000)) {
                    // We successfully published the marker! We are responsible for initializing the token.
                    logger.info("Initializing critical section token for '{}'", csName);
                    initChannel.basicPublish("", queueName, MessageProperties.PERSISTENT_TEXT_PLAIN, new byte[0]);
                } else {
                    // Publish was nacked (queue is already full because another process initialized it)
                    logger.debug("Critical section token for '{}' already initialized (nacked due to overflow)", csName);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Token initialization interrupted", e);
            } catch (TimeoutException e) {
                throw new IOException("Token initialization timed out waiting for confirms", e);
            }
        } catch (TimeoutException e) {
            throw new IOException("Failed to close initialization channel due to timeout", e);
        }
    }

    /**
     * Enters the critical section. This method blocks until the lock is acquired.
     * <p>
     * Acquisition is achieved by consuming the token message from the queue.
     * The consumer remains active until the message is received, after which it is cancelled.
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

        // Consume with manual acknowledgment (autoAck = false)
        consumerTag = channel.basicConsume(queueName, false, deliverCallback, consumerTag -> {});

        try {
            Delivery delivery = deliveryQueue.take();
            currentDeliveryTag = delivery.getEnvelope().getDeliveryTag();
            logger.debug("Successfully entered critical section '{}'", csName);
        } catch (InterruptedException e) {
            // If interrupted while waiting, check if we already retrieved the token
            Delivery delivery = deliveryQueue.poll();
            if (delivery != null) {
                // Reject and requeue the token immediately so others don't starve
                try {
                    channel.basicReject(delivery.getEnvelope().getDeliveryTag(), true);
                } catch (IOException ioex) {
                    logger.error("Failed to reject token on interrupt", ioex);
                }
            }
            throw e;
        } finally {
            // Cancel consumer immediately to stop receiving messages
            if (consumerTag != null) {
                try {
                    channel.basicCancel(consumerTag);
                } catch (IOException ioex) {
                    logger.error("Failed to cancel consumer", ioex);
                }
                consumerTag = null;
            }
        }
    }

    /**
     * Exits the critical section. This method releases the lock.
     * <p>
     * Releasing the lock is done inside a RabbitMQ transaction to atomically acknowledge (ack)
     * the held token message and publish a new token message back to the queue.
     * </p>
     *
     * @throws IOException           if a communication error occurs
     * @throws IllegalStateException  if the process is not currently in the critical section
     */
    public synchronized void exit() throws IOException {
        if (currentDeliveryTag == null) {
            throw new IllegalStateException("Process is not in critical section '" + csName + "'");
        }

        logger.debug("Exiting critical section '{}'", csName);

        try {
            channel.txSelect();
            // Publish the token back to the queue
            channel.basicPublish("", queueName, MessageProperties.PERSISTENT_TEXT_PLAIN, new byte[0]);
            // Acknowledge the delivery tag of the token we consumed
            channel.basicAck(currentDeliveryTag, false);
            channel.txCommit();
            logger.debug("Successfully exited critical section '{}'", csName);
        } catch (IOException e) {
            try {
                channel.txRollback();
            } catch (IOException rollbackEx) {
                logger.error("Failed to rollback RabbitMQ transaction during lock release", rollbackEx);
            }
            throw e;
        } finally {
            currentDeliveryTag = null;
        }
    }

    /**
     * Closes the channels and optionally the connection if it was created by this instance.
     * If the critical section is currently held, it will be automatically released/requeued
     * by RabbitMQ because the channel/connection is closed.
     *
     * @throws Exception if an error occurs while closing RabbitMQ resources
     */
    @Override
    public void close() throws Exception {
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
