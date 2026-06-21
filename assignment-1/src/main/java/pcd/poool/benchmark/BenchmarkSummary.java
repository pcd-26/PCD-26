package pcd.poool.benchmark;

import java.util.Locale;

/**
 * Aggregate statistics for a benchmark session.
 *
 * <p>The summary deliberately excludes the raw per-run measurements so callers
 * can export or inspect them separately.
 *
 * @param config configuration used to produce the measurements
 * @param totalRuns total raw runs including warmup runs
 * @param warmupRuns number of warmup runs
 * @param measuredRuns number of measured runs
 * @param successfulRuns total successful runs
 * @param failedRuns total failed runs
 * @param successfulMeasuredRuns successful measured runs
 * @param failedMeasuredRuns failed measured runs
 * @param meanElapsedMillis mean elapsed time in milliseconds
 * @param minElapsedMillis minimum elapsed time in milliseconds
 * @param maxElapsedMillis maximum elapsed time in milliseconds
 * @param stddevElapsedMillis elapsed-time standard deviation in milliseconds
 * @param meanThroughputStepsPerSecond mean throughput in completed steps per second
 * @param checksum representative checksum for the measured runs
 * @param checksumStable whether all successful measured runs produced the same checksum
 */
public record BenchmarkSummary(
        BenchmarkConfig config,
        int totalRuns,
        int warmupRuns,
        int measuredRuns,
        int successfulRuns,
        int failedRuns,
        int successfulMeasuredRuns,
        int failedMeasuredRuns,
        double meanElapsedMillis,
        double minElapsedMillis,
        double maxElapsedMillis,
        double stddevElapsedMillis,
        double meanThroughputStepsPerSecond,
        long checksum,
        boolean checksumStable) {

    @Override
    public String toString() {
        return String.format(Locale.US,
                "BenchmarkSummary{config=%s, totalRuns=%d, warmupRuns=%d, measuredRuns=%d, successfulRuns=%d, failedRuns=%d, successfulMeasuredRuns=%d, failedMeasuredRuns=%d, meanElapsedMillis=%.6f, minElapsedMillis=%.6f, maxElapsedMillis=%.6f, stddevElapsedMillis=%.6f, meanThroughputStepsPerSecond=%.3f, checksum=%d, checksumStable=%s}",
                config.toKeyValueString(),
                totalRuns,
                warmupRuns,
                measuredRuns,
                successfulRuns,
                failedRuns,
                successfulMeasuredRuns,
                failedMeasuredRuns,
                meanElapsedMillis,
                minElapsedMillis,
                maxElapsedMillis,
                stddevElapsedMillis,
                meanThroughputStepsPerSecond,
                checksum,
                checksumStable);
    }
}
