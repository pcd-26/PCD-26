package pcd.poool.benchmark.core;

import java.util.Locale;

/**
 * Optional synchronization and coordination metrics captured during a
 * benchmark run.
 *
 * <p>The values are intentionally lightweight estimates: they measure time
 * spent in coordination phases such as task submission, waiting, and
 * aggregation without changing the simulation semantics.
 *
 * @param syncTimeMillis total synchronization/coordination time in milliseconds
 * @param aggregationTimeMillis time spent merging or publishing worker results
 * @param taskSubmissionTimeMillis time spent submitting or assigning tasks
 * @param joinOrFutureWaitMillis time spent waiting for worker completion
 * @param lockAcquisitions estimated number of lock acquisitions performed
 * @param submittedTasks number of tasks submitted to workers or executors
 */
public record BenchmarkInstrumentation(
        double syncTimeMillis,
        double aggregationTimeMillis,
        double taskSubmissionTimeMillis,
        double joinOrFutureWaitMillis,
        long lockAcquisitions,
        long submittedTasks) {

    /**
     * Creates an empty instrumentation snapshot.
     *
     * @return zero-valued instrumentation
     */
    public static BenchmarkInstrumentation zero() {
        return new BenchmarkInstrumentation(0.0, 0.0, 0.0, 0.0, 0L, 0L);
    }

    /**
     * Adds another instrumentation snapshot to this one.
     *
     * @param other instrumentation to add
     * @return combined instrumentation
     */
    public BenchmarkInstrumentation plus(BenchmarkInstrumentation other) {
        if (other == null) {
            return this;
        }
        return new BenchmarkInstrumentation(
                syncTimeMillis + other.syncTimeMillis,
                aggregationTimeMillis + other.aggregationTimeMillis,
                taskSubmissionTimeMillis + other.taskSubmissionTimeMillis,
                joinOrFutureWaitMillis + other.joinOrFutureWaitMillis,
                lockAcquisitions + other.lockAcquisitions,
                submittedTasks + other.submittedTasks);
    }

    /**
     * Whether all metrics are zero.
     *
     * @return true if every field is zero
     */
    public boolean isEmpty() {
        return syncTimeMillis == 0.0
                && aggregationTimeMillis == 0.0
                && taskSubmissionTimeMillis == 0.0
                && joinOrFutureWaitMillis == 0.0
                && lockAcquisitions == 0L
                && submittedTasks == 0L;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "BenchmarkInstrumentation{syncTimeMillis=%.6f, aggregationTimeMillis=%.6f, taskSubmissionTimeMillis=%.6f, joinOrFutureWaitMillis=%.6f, lockAcquisitions=%d, submittedTasks=%d}",
                syncTimeMillis,
                aggregationTimeMillis,
                taskSubmissionTimeMillis,
                joinOrFutureWaitMillis,
                lockAcquisitions,
                submittedTasks);
    }
}
