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
 * @param stateReadTimeMillis time spent reading mutable board state into local structures
 * @param partitionTimeMillis time spent partitioning work into ranges or chunks
 * @param movementTimeMillis time spent integrating ball movement
 * @param holeInteractionTimeMillis time spent detecting or applying hole interactions
 * @param collisionDetectionTimeMillis time spent building broad-phase collision candidates
 * @param collisionResolutionTimeMillis time spent resolving collision response
 * @param mergeApplyTimeMillis time spent merging intermediate results and applying them to the board
 */
public record BenchmarkInstrumentation(
        double syncTimeMillis,
        double aggregationTimeMillis,
        double taskSubmissionTimeMillis,
        double joinOrFutureWaitMillis,
        long lockAcquisitions,
        long submittedTasks,
        double stateReadTimeMillis,
        double partitionTimeMillis,
        double movementTimeMillis,
        double holeInteractionTimeMillis,
        double collisionDetectionTimeMillis,
        double collisionResolutionTimeMillis,
        double mergeApplyTimeMillis) {

    /**
     * Creates an empty instrumentation snapshot.
     *
     * @return zero-valued instrumentation
     */
    public static BenchmarkInstrumentation zero() {
        return new BenchmarkInstrumentation(0.0, 0.0, 0.0, 0.0, 0L, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /**
     * Creates an instrumentation snapshot with only the legacy coordination fields.
     *
     * @param syncTimeMillis total synchronization/coordination time in milliseconds
     * @param aggregationTimeMillis time spent merging or publishing worker results
     * @param taskSubmissionTimeMillis time spent submitting or assigning tasks
     * @param joinOrFutureWaitMillis time spent waiting for worker completion
     * @param lockAcquisitions estimated number of lock acquisitions performed
     * @param submittedTasks number of tasks submitted to workers or executors
     */
    public BenchmarkInstrumentation(
            double syncTimeMillis,
            double aggregationTimeMillis,
            double taskSubmissionTimeMillis,
            double joinOrFutureWaitMillis,
            long lockAcquisitions,
            long submittedTasks) {
        this(
                syncTimeMillis,
                aggregationTimeMillis,
                taskSubmissionTimeMillis,
                joinOrFutureWaitMillis,
                lockAcquisitions,
                submittedTasks,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0,
                0.0);
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
                submittedTasks + other.submittedTasks,
                stateReadTimeMillis + other.stateReadTimeMillis,
                partitionTimeMillis + other.partitionTimeMillis,
                movementTimeMillis + other.movementTimeMillis,
                holeInteractionTimeMillis + other.holeInteractionTimeMillis,
                collisionDetectionTimeMillis + other.collisionDetectionTimeMillis,
                collisionResolutionTimeMillis + other.collisionResolutionTimeMillis,
                mergeApplyTimeMillis + other.mergeApplyTimeMillis);
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
                && submittedTasks == 0L
                && stateReadTimeMillis == 0.0
                && partitionTimeMillis == 0.0
                && movementTimeMillis == 0.0
                && holeInteractionTimeMillis == 0.0
                && collisionDetectionTimeMillis == 0.0
                && collisionResolutionTimeMillis == 0.0
                && mergeApplyTimeMillis == 0.0;
    }

    @Override
    public String toString() {
        return String.format(Locale.US,
                "BenchmarkInstrumentation{syncTimeMillis=%.6f, aggregationTimeMillis=%.6f, taskSubmissionTimeMillis=%.6f, joinOrFutureWaitMillis=%.6f, lockAcquisitions=%d, submittedTasks=%d, stateReadTimeMillis=%.6f, partitionTimeMillis=%.6f, movementTimeMillis=%.6f, holeInteractionTimeMillis=%.6f, collisionDetectionTimeMillis=%.6f, collisionResolutionTimeMillis=%.6f, mergeApplyTimeMillis=%.6f}",
                syncTimeMillis,
                aggregationTimeMillis,
                taskSubmissionTimeMillis,
                joinOrFutureWaitMillis,
                lockAcquisitions,
                submittedTasks,
                stateReadTimeMillis,
                partitionTimeMillis,
                movementTimeMillis,
                holeInteractionTimeMillis,
                collisionDetectionTimeMillis,
                collisionResolutionTimeMillis,
                mergeApplyTimeMillis);
    }
}
