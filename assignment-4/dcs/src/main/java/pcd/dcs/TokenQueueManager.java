package pcd.dcs;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ShutdownSignalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Handles RabbitMQ queue declaration, passive queue inspection, and channel recovery
 * for token queues used in distributed critical sections.
 * <p>
 * Detects pre-existing queues declared with incompatible AMQP arguments (which trigger
 * RabbitMQ {@code 406 PRECONDITION_FAILED} errors) and performs automatic queue repair.
 * </p>
 */
record TokenQueueManager(String queueName) {

    private static final Logger logger = LoggerFactory.getLogger(TokenQueueManager.class);
    private static final String TOKEN_QUEUE_PREFIX = "cs_token_";

    TokenQueueManager(String queueName) {
        this.queueName = TOKEN_QUEUE_PREFIX + queueName;
    }

    /**
     * Declares the token queue using the active channel.
     * Re-creates the queue if the broker rejects it due to an argument mismatch (406 PRECONDITION_FAILED).
     *
     * @param connection active RabbitMQ connection
     * @param channel    the active channel to declare on; returns a valid channel if re-created
     * @return the active, valid Channel after declaration
     * @throws IOException if declaration fails for non-precondition errors
     */
    Channel declareQueueWithRecovery(Connection connection, Channel channel) throws IOException {
        try {
            channel.queueDeclare(queueName, true, false, false, tokenQueueArguments());
            return channel;
        } catch (IOException e) {
            if (isPreconditionFailed(e)) {
                logger.warn("Queue '{}' on broker has inequivalent arguments. Re-creating queue...", queueName);
                return recreateTokenQueue(connection, channel);
            }
            throw e;
        }
    }

    private Channel recreateTokenQueue(Connection connection, Channel oldChannel) throws IOException {
        try (Channel repairChannel = connection.createChannel()) {
            repairChannel.queueDelete(queueName);
        } catch (TimeoutException e) {
            throw new IOException("Failed to close repair channel during queue recreation", e);
        }
        Channel newChannel = reopenChannel(connection, oldChannel);
        newChannel.queueDeclare(queueName, true, false, false, tokenQueueArguments());
        return newChannel;
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
    private Channel reopenChannel(Connection connection, Channel oldChannel) throws IOException {
        if (oldChannel != null && oldChannel.isOpen()) {
            try {
                oldChannel.close();
            } catch (IOException | TimeoutException ignored) {
            }
        }
        Channel newChannel = connection.createChannel();
        newChannel.basicQos(1);
        return newChannel;
    }

    /**
     * Returns the queue arguments used when declaring the token queue.
     *
     * @return {@code null} to request standard durable queue configuration without extra x-arguments
     */
    private Map<String, Object> tokenQueueArguments() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-max-length", 1);
        args.put("x-overflow", "reject-publish");
        return args;
//        return null;
    }

    /**
     * Inspects an exception hierarchy to determine whether it was caused by a RabbitMQ
     * {@code 406 PRECONDITION_FAILED} channel shutdown.
     *
     * @param e the root IOException caught during channel operations
     * @return {@code true} if the exception chain contains a 406 PRECONDITION_FAILED error
     */
    static boolean isPreconditionFailed(IOException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ShutdownSignalException sse) {
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
}
