package pcd.poool.model.physics.threaded;

/**
 * Monitor used by the controller thread to wait for a worker phase to finish.
 *
 * <p>The monitor tracks how many assigned chunks are still outstanding and
 * records the first worker failure, if any. It does not store shared work
 * results; it only acts as a phase barrier.
 */
class WorkerCompletionMonitor {

    private int remaining;
    private RuntimeException failure;

    WorkerCompletionMonitor(int remaining) {
        this.remaining = remaining;
    }

    /**
     * Marks one worker chunk as completed.
     */
    synchronized void completeOne() {
        remaining--;
        notifyAll();
    }

    /**
     * Records a worker failure and still counts the chunk as finished so the
     * coordinator can unblock and surface the error.
     */
    synchronized void fail(RuntimeException failure) {
        if (this.failure == null) {
            this.failure = failure;
        }
        remaining--;
        notifyAll();
    }

    /**
     * Waits until all assigned chunks complete or one of them fails.
     *
     * <p>The loop uses {@code while} so spurious wakeups do not let the
     * coordinator continue before the barrier is actually satisfied.
     */
    synchronized void await() {
        boolean interrupted = false;
        while (remaining > 0) {
            try {
                wait();
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
    }
}
