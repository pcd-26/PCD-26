package pcd.poool.model.physics.threaded;

/**
 * Long-lived platform thread used by {@link ThreadedPhysicsEngine}.
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
                completion.fail(ex);
            } finally {
                clearTask();
            }
        }
    }

    private synchronized Runnable awaitTask() {
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
