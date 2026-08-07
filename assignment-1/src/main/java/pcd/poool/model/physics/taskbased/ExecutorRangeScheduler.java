package pcd.poool.model.physics.taskbased;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import pcd.poool.model.physics.parallel.RangeScheduler;

/** Range scheduler backed by a fixed Executor Framework pool. */
public final class ExecutorRangeScheduler implements RangeScheduler {

    private static final int MIN_ITEMS_FOR_TASKS = 256;

    private final int poolSize;
    private final ExecutorService executor;
    private boolean closed;

    public ExecutorRangeScheduler(int poolSize) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1");
        }
        this.poolSize = poolSize;
        executor = Executors.newFixedThreadPool(poolSize, runnable -> {
            var thread = new Thread(runnable, "poool-task-physics-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public int parallelism() {
        return poolSize;
    }

    @Override
    public ExecutionStats execute(int itemCount, RangeOperation operation) {
        ensureOpen();
        long partitionStart = System.nanoTime();
        int taskCount = itemCount < MIN_ITEMS_FOR_TASKS ? 1 : Math.min(poolSize, itemCount);
        int baseSize = taskCount == 0 ? 0 : itemCount / taskCount;
        int remainder = taskCount == 0 ? 0 : itemCount % taskCount;
        long partitionNanos = System.nanoTime() - partitionStart;
        if (taskCount <= 1) {
            if (itemCount > 0) {
                operation.run(0, itemCount, 0);
            }
            return ExecutionStats.inline(partitionNanos);
        }

        long submissionStart = System.nanoTime();
        var futures = new ArrayList<Future<?>>(taskCount);
        int from = 0;
        for (int workerIndex = 0; workerIndex < taskCount; workerIndex++) {
            int size = baseSize + (workerIndex < remainder ? 1 : 0);
            int rangeStart = from;
            int rangeEnd = rangeStart + size;
            int assignedWorker = workerIndex;
            futures.add(executor.submit(
                    () -> operation.run(rangeStart, rangeEnd, assignedWorker)));
            from = rangeEnd;
        }
        long submissionNanos = System.nanoTime() - submissionStart;
        long waitStart = System.nanoTime();
        awaitAll(futures);
        long waitNanos = System.nanoTime() - waitStart;
        return new ExecutionStats(
                partitionNanos,
                submissionNanos,
                waitNanos,
                taskCount,
                taskCount + 1L);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            executor.shutdown();
        }
    }

    private void awaitAll(ArrayList<Future<?>> futures) {
        try {
            for (var future : futures) {
                future.get();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("task-based physics step interrupted", ex);
        } catch (ExecutionException ex) {
            var cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("task-based physics step failed", cause);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("executor scheduler is closed");
        }
    }
}
