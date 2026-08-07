package pcd.poool.model.physics.threaded;

/** Monitor used by the controller thread to wait for a worker phase to finish. */
class WorkerCompletionMonitor {

    private int remaining;
    private RuntimeException failure;

    WorkerCompletionMonitor(int remaining) {
        this.remaining = remaining;
    }

    /** Marks one worker chunk as completed. */
    synchronized void completeOne() {
        remaining--;
        notifyAll();
    }

    /** Records a worker failure and still lets the coordinator unblock. */
    synchronized void fail(RuntimeException failure) {
        if (this.failure == null) {
            this.failure = failure;
        }
        remaining--;
        notifyAll();
    }

    /** Waits until all assigned chunks complete or one of them fails. */
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
