package pcd.poool.model.physics.threaded;

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
