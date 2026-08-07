package pcd.poool.model.physics.parallel;

/** Executes independent contiguous ranges and waits for the whole phase. */
public interface RangeScheduler extends AutoCloseable {

    int parallelism();

    ExecutionStats execute(int itemCount, RangeOperation operation);

    @Override
    void close();

    @FunctionalInterface
    interface RangeOperation {
        void run(int fromInclusive, int toExclusive, int workerIndex);
    }

    record ExecutionStats(
            long partitionNanos,
            long submissionNanos,
            long waitNanos,
            long submittedTasks,
            long coordinationOperations) {

        public static ExecutionStats inline(long partitionNanos) {
            return new ExecutionStats(partitionNanos, 0, 0, 0, 0);
        }
    }
}
