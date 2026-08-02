package pcd.dcs;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles RabbitMQ queue declaration, passive queue inspection, and channel recovery
 * for token queues used in distributed critical sections.
 * <p>
 * Detects pre-existing queues declared with incompatible AMQP arguments (which trigger
 * RabbitMQ {@code 406 PRECONDITION_FAILED} errors) and performs automatic queue repair.
 * </p>
 */
record TokenQueueManager(String queueName) {
    private static final String TOKEN_QUEUE_PREFIX = "cs_token_";

    TokenQueueManager(String queueName) {
        this.queueName = TOKEN_QUEUE_PREFIX + queueName;
    }

    /**
     * Declares the token queue using the active channel.
     * If the broker rejects the declaration because the queue already exists with incompatible
     * arguments, the method fails fast rather than deleting broker state.
     *
     * @param channel the active channel to declare on
     * @return the active, valid Channel after declaration
     * @throws IOException if declaration fails for non-precondition errors or incompatible broker state
     */
    Channel declareQueue(Channel channel) throws IOException {
        try {
            channel.queueDeclare(queueName, true, false, false, tokenQueueArguments());
            return channel;
        } catch (IOException e) {
            if (isPreconditionFailed(e)) {
                throw new IOException(
                        "Queue '" + queueName + "' already exists with incompatible broker arguments. "
                                + "Please clean up the queue manually before restarting the node.",
                        e);
            }
            throw e;
        }
    }

    /**
     * Returns the queue arguments used when declaring the token queue.
     *
     * @return queue arguments that constrain the token queue to a single message
     */
    private Map<String, Object> tokenQueueArguments() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-max-length", 1);
        args.put("x-overflow", "reject-publish");
        return args;
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
