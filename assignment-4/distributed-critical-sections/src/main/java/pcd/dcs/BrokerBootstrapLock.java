package pcd.dcs;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Broker-side mutex used to guard token bootstrap and release transitions.
class BrokerBootstrapLock {

    private static final Logger logger = LoggerFactory.getLogger(BrokerBootstrapLock.class);
    private static final long DEFAULT_LOCK_RETRY_DELAY_MILLIS = 50L;
    private static final long DEFAULT_LOCK_TIMEOUT_MILLIS = 10_000L;

    // Operation executed while the temporary lock queue is owned.
    @FunctionalInterface
    interface Action {
        void run(Channel tokenBootstrapGuardChannel) throws IOException, InterruptedException;
    }

    private final String tokenBootstrapGuardQueue;
    private final long timeoutMillis;
    private final long retryDelayMillis;

    BrokerBootstrapLock(String csName) {
        this(csName, DEFAULT_LOCK_TIMEOUT_MILLIS, DEFAULT_LOCK_RETRY_DELAY_MILLIS);
    }

    BrokerBootstrapLock(String csName, long timeoutMillis, long retryDelayMillis) {
        this.tokenBootstrapGuardQueue = "cs_token_bootstrap_guard_" + csName;
        this.timeoutMillis = timeoutMillis;
        this.retryDelayMillis = retryDelayMillis;
    }

    // Retry until the temporary lock queue can be declared, then run the protected action.
    void withLock(Connection connection, Action action) throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        while (true) {
            // Preserve interruption semantics while waiting for the broker lock.
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while acquiring bootstrap lock for '" + tokenBootstrapGuardQueue + "'");
            }

            Channel tokenBootstrapGuardChannel = connection.createChannel();
            try {
                try {
                    // The exclusive queue declaration succeeds for one process at a time.
                    tokenBootstrapGuardChannel.queueDeclare(tokenBootstrapGuardQueue, false, true, false, null);
                } catch (IOException lockFailure) {
                    if (System.nanoTime() >= deadlineNanos) {
                        throw new IOException(
                                "Timed out acquiring bootstrap lock for '" + tokenBootstrapGuardQueue + "'",
                                lockFailure);
                    }
                    // Another process still owns the lock; wait and retry.
                    TimeUnit.MILLISECONDS.sleep(retryDelayMillis);
                    continue;
                }

                try {
                    // Run the critical transition while the queue guarantees exclusive ownership.
                    action.run(tokenBootstrapGuardChannel);
                } finally {
                    try {
                        // Explicitly delete the lock queue instead of waiting for connection teardown.
                        tokenBootstrapGuardChannel.queueDelete(tokenBootstrapGuardQueue);
                    } catch (IOException cleanupFailure) {
                        logger.warn("Failed to delete bootstrap lock queue '{}'", tokenBootstrapGuardQueue, cleanupFailure);
                    }
                }
                return;
            } finally {
                try {
                    if (tokenBootstrapGuardChannel.isOpen()) {
                        // Each acquisition attempt uses a short-lived channel.
                        tokenBootstrapGuardChannel.close();
                    }
                } catch (IOException | TimeoutException cleanupFailure) {
                    logger.warn("Failed to close bootstrap lock channel for '{}'", tokenBootstrapGuardQueue, cleanupFailure);
                }
            }
        }
    }
}
