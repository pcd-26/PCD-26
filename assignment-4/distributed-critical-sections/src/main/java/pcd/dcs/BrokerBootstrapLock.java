package pcd.dcs;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Manages temporary, exclusive broker-side lock queues to serialize critical operations
 * (such as bootstrap initialization and safe release transitions) across distributed processes.
 * <p>
 * The lock is materialized as an exclusive, auto-deleted queue {@code cs_bootstrap_lock_<csName>}.
 * Concurrent processes attempt to declare this queue. Exactly one process succeeds at a time;
 * others fail with an {@link IOException} and poll with a delay until the lock is released or the timeout expires.
 * </p>
 */
class BrokerBootstrapLock {

    private static final Logger logger = LoggerFactory.getLogger(BrokerBootstrapLock.class);
    private static final long DEFAULT_LOCK_RETRY_DELAY_MILLIS = 50L;
    private static final long DEFAULT_LOCK_TIMEOUT_MILLIS = 10_000L;

    @FunctionalInterface
    interface Action {
        void run(Channel lockChannel) throws IOException, InterruptedException;
    }

    private final String bootstrapLockQueue;
    private final long timeoutMillis;
    private final long retryDelayMillis;

    BrokerBootstrapLock(String csName) {
        this(csName, DEFAULT_LOCK_TIMEOUT_MILLIS, DEFAULT_LOCK_RETRY_DELAY_MILLIS);
    }

    BrokerBootstrapLock(String csName, long timeoutMillis, long retryDelayMillis) {
        this.bootstrapLockQueue = "cs_bootstrap_lock_" + csName;
        this.timeoutMillis = timeoutMillis;
        this.retryDelayMillis = retryDelayMillis;
    }

    /**
     * Executes the given action while holding the exclusive broker lock queue.
     * <p>
     * Polling is un-synchronized to avoid holding monitor locks during retry delay sleeps.
     * </p>
     *
     * @param connection the active RabbitMQ connection
     * @param action     the callback action to execute under lock ownership
     * @throws IOException          if lock acquisition times out or action execution fails
     * @throws InterruptedException if the waiting thread is interrupted during retry polling
     */
    void withLock(Connection connection, Action action) throws IOException, InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interrupted while acquiring bootstrap lock for '" + bootstrapLockQueue + "'");
            }

            Channel lockChannel = connection.createChannel();
            try {
                try {
                    lockChannel.queueDeclare(bootstrapLockQueue, false, true, false, null);
                } catch (IOException lockFailure) {
                    if (System.nanoTime() >= deadlineNanos) {
                        throw new IOException(
                                "Timed out acquiring bootstrap lock for '" + bootstrapLockQueue + "'",
                                lockFailure);
                    }
                    TimeUnit.MILLISECONDS.sleep(retryDelayMillis);
                    continue;
                }

                try {
                    action.run(lockChannel);
                } finally {
                    try {
                        lockChannel.queueDelete(bootstrapLockQueue);
                    } catch (IOException cleanupFailure) {
                        logger.warn("Failed to delete bootstrap lock queue '{}'", bootstrapLockQueue, cleanupFailure);
                    }
                }
                return;
            } finally {
                try {
                    if (lockChannel.isOpen()) {
                        lockChannel.close();
                    }
                } catch (IOException | TimeoutException cleanupFailure) {
                    logger.warn("Failed to close bootstrap lock channel for '{}'", bootstrapLockQueue, cleanupFailure);
                }
            }
        }
    }
}
