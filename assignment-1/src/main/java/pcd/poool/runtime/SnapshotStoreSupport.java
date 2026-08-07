package pcd.poool.runtime;

import java.time.Duration;
import java.util.function.Predicate;

// Monitor that stores the latest immutable snapshot published by the controller.
public class SnapshotStoreSupport<S> {

    private S snapshot;

    public SnapshotStoreSupport(S initialSnapshot) {
        snapshot = initialSnapshot;
    }

    // Publishes a new snapshot.
    public synchronized void publish(S snapshot) {
        this.snapshot = snapshot;
        notifyAll();
    }

    // Returns the latest snapshot.
    public synchronized S get() {
        return snapshot;
    }

    // Waits until a snapshot satisfies the given predicate.
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
