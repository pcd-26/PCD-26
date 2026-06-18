package pcd.poool.taskbased;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Monitor that stores the latest immutable snapshot published by the
 * simulation controller.
 */
public class SnapshotStore {

    private TaskBasedGameSnapshot snapshot;

    SnapshotStore(TaskBasedGameSnapshot initialSnapshot) {
        snapshot = initialSnapshot;
    }

    public synchronized void publish(TaskBasedGameSnapshot snapshot) {
        this.snapshot = snapshot;
        notifyAll();
    }

    public synchronized TaskBasedGameSnapshot get() {
        return snapshot;
    }

    public synchronized TaskBasedGameSnapshot awaitUntil(
            Predicate<TaskBasedGameSnapshot> predicate,
            Duration timeout) throws InterruptedException {
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
