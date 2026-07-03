package pcd.poool.model.physics.threaded;

/**
 * Long-lived platform thread used by {@link ThreadedPhysicsEngine}.
 *
 * <p>The worker owns one task slot guarded by its monitor. The engine assigns
 * a phase chunk, waits on a separate completion monitor, and only then reuses
 * the worker for the next chunk.
 */
class PhysicsWorker implements AutoCloseable {

    private static final long JOIN_TIMEOUT_MILLIS = 1_000;

    private final Thread thread;
    private Runnable task;
    private WorkerCompletionMonitor completion;
    private boolean running;

    PhysicsWorker(String name) {
        running = true;
        thread = new Thread(this::loop, name);
        thread.start();
    }

    /**
     * Assigns one phase chunk to this worker.
     *
     * <p>The monitor wait uses a {@code while} loop so the caller does not
     * observe a stale task slot after a spurious wakeup.
     */
    synchronized void assign(Runnable task, WorkerCompletionMonitor completion) {
        while (this.task != null && running) {
            try {
                wait();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while assigning physics work", ex);
            }
        }
        if (!running) {
            throw new IllegalStateException("physics worker is stopped");
        }
        this.task = task;
        this.completion = completion;
        notifyAll();
    }

    @Override
    public void close() {
        // Stop accepting new work, wake the thread, and join it outside the
        // monitor so shutdown cannot deadlock on the worker lock.
        synchronized (this) {
            running = false;
            notifyAll();
            thread.interrupt();
        }
        try {
            thread.join(JOIN_TIMEOUT_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        while (true) {
            var assignedTask = awaitTask();
            if (assignedTask == null) {
                return;
            }
            try {
                assignedTask.run();
                completion.completeOne();
            } catch (RuntimeException ex) {
                // The first failure is enough for the coordinator; the
                // completion monitor keeps the phase barrier consistent.
                completion.fail(ex);
            } finally {
                clearTask();
            }
        }
    }

    private synchronized Runnable awaitTask() {
        // Wait until the engine assigns a task or the worker is shut down.
        while (task == null && running) {
            try {
                wait();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
        return running ? task : null;
    }

    private synchronized void clearTask() {
        task = null;
        completion = null;
        notifyAll();
    }
}
