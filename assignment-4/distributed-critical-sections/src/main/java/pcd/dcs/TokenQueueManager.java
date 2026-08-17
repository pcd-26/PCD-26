package pcd.dcs;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// Declare and validate the single-message queue that stores the critical-section token.
record TokenQueueManager(String criticalSectionTokenQueue) {
    private static final String CRITICAL_SECTION_TOKEN_QUEUE_PREFIX = "cs_token_";

    // Prefix the logical critical-section name with the broker queue namespace.
    TokenQueueManager(String criticalSectionTokenQueue) {
        this.criticalSectionTokenQueue = CRITICAL_SECTION_TOKEN_QUEUE_PREFIX + criticalSectionTokenQueue;
    }

    // Create the token queue with the expected broker constraints.
    Channel declareQueue(Channel channel) throws IOException {
        try {
            channel.queueDeclare(criticalSectionTokenQueue, true, false, false, tokenQueueArguments());
            return channel;
        } catch (IOException e) {
            if (isPreconditionFailed(e)) {
                // Stop immediately if the broker already hosts an incompatible queue.
                throw new IOException(
                        "Queue '" + criticalSectionTokenQueue + "' already exists with incompatible broker arguments. "
                                + "Please clean up the queue manually before restarting the node.",
                        e);
            }
            throw e;
        }
    }

    // Enforce the invariant that at most one token can exist in the queue.
    private Map<String, Object> tokenQueueArguments() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-max-length", 1);
        args.put("x-overflow", "reject-publish");
        return args;
    }

    // Detect the RabbitMQ error used when queue arguments do not match an existing declaration.
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
