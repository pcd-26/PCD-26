package pcd.poool.verification.jpf;

/**
 * Shared state for the minimal JPF protocol models.
 *
 * <p>The state is intentionally tiny so that JPF can explore all the relevant
 * interleavings without being overwhelmed by the full game model.
 */
final class MinimalProtocolState {

    private final Object lock = new Object();

    private int pendingCommands;
    private boolean boardOwned;
    private boolean workReady;
    private boolean workerDone;
    private boolean snapshotPublished;
    private boolean finished;

    Object lock() {
        return lock;
    }

    void submitCommand() {
        synchronized (lock) {
            pendingCommands++;
            lock.notifyAll();
        }
    }

    void acquireBoardOwnership() {
        synchronized (lock) {
            if (boardOwned) {
                throw new AssertionError("board is already owned");
            }
            boardOwned = true;
        }
    }

    void drainCommands() {
        synchronized (lock) {
            pendingCommands = 0;
        }
    }

    void prepareWork() {
        synchronized (lock) {
            workReady = true;
            lock.notifyAll();
        }
    }

    void waitForWorkAndComplete() throws InterruptedException {
        synchronized (lock) {
            while (!workReady) {
                lock.wait();
            }
            if (!boardOwned) {
                throw new AssertionError("worker accessed board without ownership");
            }
            workerDone = true;
            lock.notifyAll();
        }
    }

    void awaitWorkerAndPublish() throws InterruptedException {
        synchronized (lock) {
            while (!workerDone) {
                lock.wait();
            }
            if (pendingCommands != 0) {
                throw new AssertionError("commands were not drained before publish");
            }
            snapshotPublished = true;
            boardOwned = false;
            finished = true;
            lock.notifyAll();
        }
    }

    void waitUntilCommandArrives() throws InterruptedException {
        synchronized (lock) {
            while (pendingCommands == 0) {
                lock.wait();
            }
        }
    }

    void assertFinalState() {
        synchronized (lock) {
            if (boardOwned) {
                throw new AssertionError("board ownership was not released");
            }
            if (pendingCommands != 0) {
                throw new AssertionError("pending commands remain after completion");
            }
            if (!snapshotPublished) {
                throw new AssertionError("snapshot was not published");
            }
            if (!finished) {
                throw new AssertionError("protocol did not reach finished state");
            }
        }
    }
}
