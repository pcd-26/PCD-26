package pcd.poool.model.physics.threaded;

import pcd.poool.model.physics.parallel.RangeScheduler;

/** Range scheduler backed by reusable, explicitly managed platform threads. */
public final class PlatformThreadRangeScheduler implements RangeScheduler {

    private final PhysicsWorker[] workers;
    private boolean closed;

    public PlatformThreadRangeScheduler(int workerCount) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be >= 1");
        }
        workers = new PhysicsWorker[workerCount];
        for (int i = 0; i < workerCount; i++) {
            workers[i] = new PhysicsWorker("poool-physics-worker-" + i);
        }
    }

    @Override
    public int parallelism() {
        return workers.length;
    }

    @Override
    public ExecutionStats execute(int itemCount, RangeOperation operation) {
        ensureOpen();
        long partitionStart = System.nanoTime();
        int usedWorkers = Math.min(workers.length, itemCount);
        if (usedWorkers <= 1) {
            long partitionNanos = System.nanoTime() - partitionStart;
            if (itemCount > 0) {
                operation.run(0, itemCount, 0);
            }
            return ExecutionStats.inline(partitionNanos);
        }

        int baseSize = itemCount / usedWorkers;
        int remainder = itemCount % usedWorkers;
        long partitionNanos = System.nanoTime() - partitionStart;
        var completion = new WorkerCompletionMonitor(usedWorkers);
        long submissionStart = System.nanoTime();
        int from = 0;
        for (int workerIndex = 0; workerIndex < usedWorkers; workerIndex++) {
            int size = baseSize + (workerIndex < remainder ? 1 : 0);
            int rangeStart = from;
            int rangeEnd = rangeStart + size;
            int assignedWorker = workerIndex;
            workers[workerIndex].assign(
                    () -> operation.run(rangeStart, rangeEnd, assignedWorker),
                    completion);
            from = rangeEnd;
        }
        long submissionNanos = System.nanoTime() - submissionStart;
        long waitStart = System.nanoTime();
        completion.await();
        long waitNanos = System.nanoTime() - waitStart;
        return new ExecutionStats(
                partitionNanos,
                submissionNanos,
                waitNanos,
                usedWorkers,
                usedWorkers + 1L);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (var worker : workers) {
            worker.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("platform-thread scheduler is closed");
        }
    }
}
