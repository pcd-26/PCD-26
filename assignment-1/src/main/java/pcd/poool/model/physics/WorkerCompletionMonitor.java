package pcd.poool.model.physics;

/**
 * Monitor used by the controller thread to wait for a worker phase to finish.
 */
class WorkerCompletionMonitor {

    private int remaining;
    private RuntimeException failure;

    WorkerCompletionMonitor(int remaining) {
        this.remaining = remaining;
    }

    synchronized void completeOne() {
        remaining--;
        notifyAll();
    }

    synchronized void fail(RuntimeException failure) {
        if (this.failure == null) {
            this.failure = failure;
        }
        remaining--;
        notifyAll();
    }

    synchronized void await() {
        while (remaining > 0) {
            try {
                wait();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for physics workers", ex);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
