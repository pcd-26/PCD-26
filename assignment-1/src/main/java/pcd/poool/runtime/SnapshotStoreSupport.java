package pcd.poool.runtime;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Monitor that stores the latest immutable snapshot published by the
 * simulation controller.
 *
 * @param <S> snapshot type
 */
public class SnapshotStoreSupport<S> {

    private S snapshot;

    public SnapshotStoreSupport(S initialSnapshot) {
        snapshot = initialSnapshot;
    }

    /**
     * Publishes a new snapshot and wakes readers waiting for state changes.
     *
     * @param snapshot latest immutable state
     */
    public synchronized void publish(S snapshot) {
        this.snapshot = snapshot;
        notifyAll();
    }

    /**
     * Gets the latest snapshot.
     *
     * @return latest published snapshot
     */
    public synchronized S get() {
        return snapshot;
    }

    /**
     * Waits until a snapshot satisfies the given predicate.
     *
     * @param predicate condition evaluated on the latest snapshot
     * @param timeout maximum wait duration
     * @return matching snapshot
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the timeout expires
     */
    public synchronized S awaitUntil(Predicate<S> predicate, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!predicate.test(snapshot)) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new IllegalStateException("snapshot condition was not reached before timeout");
            }
            wait(remaining);
        }
        return snapshot;
    }
}
