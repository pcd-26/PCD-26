package pcd.poool.benchmark.core;

import java.util.Locale;

/** Raw measurement produced by one benchmark run. */
public record BenchmarkRunResult(
        int runIndex,
        boolean warmup,
        long elapsedNanos,
        double elapsedMillis,
        int completedSteps,
        double throughputStepsPerSecond,
        long checksum,
        BenchmarkInstrumentation instrumentation,
        double cpuUtilizationPercent,
        Status status,
        String failureMessage) {

    /** Run status. */
    public enum Status {
        SUCCESS,
        FAILED
    }

    /** Creates a successful run result. */
    public static BenchmarkRunResult success(
            int runIndex,
            boolean warmup,
            long elapsedNanos,
            int completedSteps,
            long checksum) {
        return success(runIndex, warmup, elapsedNanos, completedSteps, checksum, BenchmarkInstrumentation.zero());
    }

    /** Creates a successful run result. */
    public static BenchmarkRunResult success(
            int runIndex,
            boolean warmup,
            long elapsedNanos,
            int completedSteps,
            long checksum,
            BenchmarkInstrumentation instrumentation) {
        return success(runIndex, warmup, elapsedNanos, completedSteps, checksum, instrumentation, Double.NaN);
    }

    /** Creates a successful run result. */
    public static BenchmarkRunResult success(
            int runIndex,
            boolean warmup,
            long elapsedNanos,
            int completedSteps,
            long checksum,
            BenchmarkInstrumentation instrumentation,
            double cpuUtilizationPercent) {
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
                instrumentation == null ? BenchmarkInstrumentation.zero() : instrumentation,
                cpuUtilizationPercent,
                Status.SUCCESS,
                null);
    }

    /** Creates a failed run result. */
    public static BenchmarkRunResult failure(int runIndex, boolean warmup, long elapsedNanos, String failureMessage) {
        return failure(runIndex, warmup, elapsedNanos, failureMessage, BenchmarkInstrumentation.zero());
    }

    /** Creates a failed run result. */
    public static BenchmarkRunResult failure(
            int runIndex,
            boolean warmup,
            long elapsedNanos,
            String failureMessage,
            BenchmarkInstrumentation instrumentation) {
        return failure(runIndex, warmup, elapsedNanos, failureMessage, instrumentation, Double.NaN);
    }

    /** Creates a failed run result. */
    public static BenchmarkRunResult failure(
            int runIndex,
            boolean warmup,
            long elapsedNanos,
            String failureMessage,
            BenchmarkInstrumentation instrumentation,
            double cpuUtilizationPercent) {
        double elapsedMillis = elapsedNanos / BenchmarkRunner.NANOS_PER_MILLISECOND;
        return new BenchmarkRunResult(
                runIndex,
                warmup,
                elapsedNanos,
                elapsedMillis,
                0,
                0.0,
                0L,
                instrumentation == null ? BenchmarkInstrumentation.zero() : instrumentation,
                cpuUtilizationPercent,
                Status.FAILED,
                failureMessage);
    }

    /** Returns whether the run succeeded. */
    public boolean succeeded() {
        return status == Status.SUCCESS;
    }

    /** Returns whether the run failed. */
    public boolean failed() {
        return status == Status.FAILED;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "BenchmarkRunResult{runIndex=%d, warmup=%s, elapsedMillis=%.6f, completedSteps=%d, throughput=%.3f, checksum=%d, instrumentation=%s, cpuUtilizationPercent=%.3f, status=%s}",
                runIndex,
                warmup,
                elapsedMillis,
                completedSteps,
                throughputStepsPerSecond,
                checksum,
                instrumentation,
                cpuUtilizationPercent,
                status);
    }
}
