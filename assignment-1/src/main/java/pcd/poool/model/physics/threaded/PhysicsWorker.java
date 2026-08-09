package pcd.poool.model.physics.threaded;

/** Long-lived platform thread owned directly by the threaded physics engine. */
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

    /** Assigns one contiguous range of work to this worker. */
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
        // Stop work, wake the thread, and join it outside the lock.
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
                // Run the assigned chunk and notify the barrier when it finishes.
                assignedTask.run();
                completion.completeOne();
            } catch (RuntimeException ex) {
                // One failure is enough; the barrier stays consistent.
                completion.fail(ex);
            } finally {
                clearTask();
            }
        }
    }

    private synchronized Runnable awaitTask() {
        // Wait for work or shutdown.
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
