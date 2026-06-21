package pcd.poool.benchmark;

import java.util.Locale;

/**
 * Raw measurement produced by one benchmark run.
 *
 * @param runIndex 1-based run index within the benchmark session
 * @param warmup whether the run belongs to the warmup phase
 * @param elapsedNanos elapsed wall-clock time in nanoseconds
 * @param elapsedMillis elapsed wall-clock time in milliseconds
 * @param completedSteps number of completed simulation steps
 * @param throughputStepsPerSecond completed steps divided by elapsed seconds
 * @param checksum checksum or state hash observed at the end of the run
 * @param status run status
 * @param failureMessage optional failure detail, null for successful runs
 */
public record BenchmarkRunResult(
        int runIndex,
        boolean warmup,
        long elapsedNanos,
        double elapsedMillis,
        int completedSteps,
        double throughputStepsPerSecond,
        long checksum,
        Status status,
        String failureMessage) {

    /**
     * Run status.
     */
    public enum Status {
        SUCCESS,
        FAILED
    }

    /**
     * Creates a successful run result.
     *
     * @param runIndex 1-based run index
     * @param warmup whether the run belongs to the warmup phase
     * @param elapsedNanos elapsed wall-clock time in nanoseconds
     * @param completedSteps number of completed simulation steps
     * @param checksum checksum or state hash observed at the end of the run
     * @return successful run result
     */
    public static BenchmarkRunResult success(
            int runIndex,
            boolean warmup,
            long elapsedNanos,
            int completedSteps,
            long checksum) {
        double elapsedMillis = elapsedNanos / BenchmarkRunner.NANOS_PER_MILLISECOND;
        double throughput = BenchmarkRunner.throughput(completedSteps, elapsedNanos);
        return new BenchmarkRunResult(
                runIndex,
                warmup,
                elapsedNanos,
                elapsedMillis,
                completedSteps,
                throughput,
                checksum,
                Status.SUCCESS,
                null);
    }

    /**
     * Creates a failed run result.
     *
     * @param runIndex 1-based run index
     * @param warmup whether the run belongs to the warmup phase
     * @param elapsedNanos elapsed wall-clock time in nanoseconds
     * @param failureMessage failure detail
     * @return failed run result
     */
    public static BenchmarkRunResult failure(int runIndex, boolean warmup, long elapsedNanos, String failureMessage) {
        double elapsedMillis = elapsedNanos / BenchmarkRunner.NANOS_PER_MILLISECOND;
        return new BenchmarkRunResult(
                runIndex,
                warmup,
                elapsedNanos,
                elapsedMillis,
                0,
                0.0,
                0L,
                Status.FAILED,
                failureMessage);
    }

    /**
     * Checks whether this result represents a successful run.
     *
     * @return whether the run succeeded
     */
    public boolean succeeded() {
        return status == Status.SUCCESS;
    }

    /**
     * Checks whether this result represents a failed run.
     *
     * @return whether the run failed
     */
    public boolean failed() {
        return status == Status.FAILED;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "BenchmarkRunResult{runIndex=%d, warmup=%s, elapsedMillis=%.6f, completedSteps=%d, throughput=%.3f, checksum=%d, status=%s}",
                runIndex,
                warmup,
                elapsedMillis,
                completedSteps,
                throughputStepsPerSecond,
                checksum,
                status);
    }
}
