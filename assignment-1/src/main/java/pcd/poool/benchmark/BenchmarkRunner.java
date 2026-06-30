package pcd.poool.benchmark;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared infrastructure for benchmark timing and aggregation.
 *
 * <p>The runner keeps raw measurements and summary statistics separate so the
 * benchmark can export both the per-run evidence and the aggregate view.
 */
public final class BenchmarkRunner {

    public static final double NANOS_PER_MILLISECOND = 1_000_000.0;
    public static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private BenchmarkRunner() {
    }

    /**
     * Benchmark workload executed inside a timed run.
     */
    @FunctionalInterface
    public interface BenchmarkWorkload {

        /**
         * Executes the benchmark payload and returns the resulting checksum.
         *
         * @return checksum or state hash produced by the workload
         * @throws Exception if the workload fails
         */
        long run() throws Exception;
    }

    /**
     * Benchmark workload executed inside a timed run and returning both a
     * checksum and optional instrumentation.
     */
    @FunctionalInterface
    public interface BenchmarkExecutionWorkload {

        /**
         * Executes the benchmark payload.
         *
         * @return execution result and optional instrumentation
         * @throws Exception if the workload fails
         */
        BenchmarkExecution run() throws Exception;
    }

    /**
     * Result returned by an execution workload.
     *
     * @param checksum checksum or state hash produced by the workload
     * @param instrumentation optional synchronization metrics for the run
     */
    public record BenchmarkExecution(
            long checksum,
            BenchmarkInstrumentation instrumentation,
            BenchmarkStateFingerprint fingerprint) {
        public BenchmarkExecution {
            if (instrumentation == null) {
                instrumentation = BenchmarkInstrumentation.zero();
            }
            if (fingerprint == null) {
                fingerprint = BenchmarkStateFingerprint.unknown(checksum);
            }
        }

        public BenchmarkExecution(long checksum, BenchmarkInstrumentation instrumentation) {
            this(checksum, instrumentation, BenchmarkStateFingerprint.unknown(checksum));
        }
    }

    /**
     * Measures one benchmark run.
     *
     * @param runIndex 1-based run index
     * @param warmup whether the run belongs to the warmup phase
     * @param completedSteps number of simulation steps completed by the run
     * @param workload timed workload
     * @return raw run result
     */
    public static BenchmarkRunResult time(
            int runIndex,
            boolean warmup,
            int completedSteps,
            BenchmarkWorkload workload) {
        return time(runIndex, warmup, completedSteps,
                () -> new BenchmarkExecution(workload.run(), BenchmarkInstrumentation.zero()));
    }

    /**
     * Measures one benchmark run and captures optional instrumentation.
     *
     * @param runIndex 1-based run index
     * @param warmup whether the run belongs to the warmup phase
     * @param completedSteps number of simulation steps completed by the run
     * @param workload timed workload
     * @return raw run result
     */
    public static BenchmarkRunResult time(
            int runIndex,
            boolean warmup,
            int completedSteps,
            BenchmarkExecutionWorkload workload) {
        long start = System.nanoTime();
        Long cpuStart = processCpuTimeNanos();
        try {
            BenchmarkExecution execution = workload.run();
            long elapsedNanos = System.nanoTime() - start;
            double cpuUtilization = cpuUtilizationPercent(cpuStart, processCpuTimeNanos(), elapsedNanos);
            return BenchmarkRunResult.success(
                    runIndex,
                    warmup,
                    elapsedNanos,
                    completedSteps,
                    execution.checksum(),
                    execution.instrumentation(),
                    cpuUtilization);
        } catch (Exception ex) {
            long elapsedNanos = System.nanoTime() - start;
            double cpuUtilization = cpuUtilizationPercent(cpuStart, processCpuTimeNanos(), elapsedNanos);
            return BenchmarkRunResult.failure(runIndex, warmup, elapsedNanos, failureMessage(ex), BenchmarkInstrumentation.zero(), cpuUtilization);
        }
    }

    /**
     * Executes the warmup and measured runs described by a configuration.
     *
     * @param config benchmark configuration
     * @param workload benchmark workload
     * @return raw results for every run, including warmup runs
     */
    public static List<BenchmarkRunResult> execute(BenchmarkConfig config, BenchmarkWorkload workload) {
        var results = new ArrayList<BenchmarkRunResult>(config.warmupRuns() + config.measuredRuns());
        int runIndex = 1;
        for (int i = 0; i < config.warmupRuns(); i++) {
            results.add(time(runIndex++, true, config.steps(), workload));
        }
        for (int i = 0; i < config.measuredRuns(); i++) {
            results.add(time(runIndex++, false, config.steps(), workload));
        }
        return List.copyOf(results);
    }

    /**
     * Executes and summarizes a benchmark session.
     *
     * @param config benchmark configuration
     * @param workload benchmark workload
     * @return aggregate summary excluding warmup samples
     */
    public static BenchmarkSummary run(BenchmarkConfig config, BenchmarkWorkload workload) {
        return summarize(config, execute(config, workload));
    }

    /**
     * Builds a summary from raw benchmark results.
     *
     * @param config benchmark configuration used for the session
     * @param results raw results from warmup and measured runs
     * @return aggregate summary
     */
    public static BenchmarkSummary summarize(BenchmarkConfig config, List<BenchmarkRunResult> results) {
        int totalRuns = results.size();
        int warmupRuns = 0;
        int measuredRuns = 0;
        int successfulRuns = 0;
        int failedRuns = 0;
        int successfulMeasuredRuns = 0;
        int failedMeasuredRuns = 0;
        var elapsedSamples = new ArrayList<Double>();
        var throughputSamples = new ArrayList<Double>();
        var cpuUtilizationSamples = new ArrayList<Double>();
        Long checksum = null;
        boolean checksumStable = true;

        for (var result : results) {
            if (result.warmup()) {
                warmupRuns++;
            } else {
                measuredRuns++;
            }

            if (result.succeeded()) {
                successfulRuns++;
                if (!result.warmup()) {
                    successfulMeasuredRuns++;
                    elapsedSamples.add(result.elapsedMillis());
                    throughputSamples.add(result.throughputStepsPerSecond());
                    if (!Double.isNaN(result.cpuUtilizationPercent())) {
                        cpuUtilizationSamples.add(result.cpuUtilizationPercent());
                    }
                    if (checksum == null) {
                        checksum = result.checksum();
                    } else if (!checksum.equals(result.checksum())) {
                        checksumStable = false;
                    }
                }
            } else {
                failedRuns++;
                if (!result.warmup()) {
                    failedMeasuredRuns++;
                }
            }
        }

        double meanElapsedMillis = mean(elapsedSamples);
        double medianElapsedMillis = median(elapsedSamples);
        double minElapsedMillis = min(elapsedSamples);
        double maxElapsedMillis = max(elapsedSamples);
        double stddevElapsedMillis = stddev(elapsedSamples, meanElapsedMillis);
        double meanThroughput = mean(throughputSamples);
        double medianThroughput = median(throughputSamples);
        double meanCpuUtilization = mean(cpuUtilizationSamples);
        double medianCpuUtilization = median(cpuUtilizationSamples);

        return new BenchmarkSummary(
                config,
                totalRuns,
                warmupRuns,
                measuredRuns,
                successfulRuns,
                failedRuns,
                successfulMeasuredRuns,
                failedMeasuredRuns,
                meanElapsedMillis,
                medianElapsedMillis,
                minElapsedMillis,
                maxElapsedMillis,
                stddevElapsedMillis,
                meanThroughput,
                medianThroughput,
                meanCpuUtilization,
                medianCpuUtilization,
                checksum == null ? 0L : checksum,
                checksum != null && checksumStable);
    }

    /**
     * Computes throughput in completed steps per second.
     *
     * @param completedSteps number of completed simulation steps
     * @param elapsedNanos elapsed time in nanoseconds
     * @return completed steps divided by elapsed seconds
     */
    public static double throughput(int completedSteps, long elapsedNanos) {
        if (elapsedNanos <= 0L) {
            return 0.0;
        }
        return completedSteps * NANOS_PER_SECOND / elapsedNanos;
    }

    private static String failureMessage(Exception ex) {
        var message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return String.format(Locale.US, "%s: %s", ex.getClass().getSimpleName(), message);
    }

    private static Long processCpuTimeNanos() {
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean extendedBean) {
            long value = extendedBean.getProcessCpuTime();
            if (value >= 0L) {
                return value;
            }
        }
        return null;
    }

    private static double cpuUtilizationPercent(Long startCpuNanos, Long endCpuNanos, long elapsedNanos) {
        if (startCpuNanos == null || endCpuNanos == null || elapsedNanos <= 0L) {
            return Double.NaN;
        }
        long cpuDelta = Math.max(0L, endCpuNanos - startCpuNanos);
        if (cpuDelta <= 0L) {
            return Double.NaN;
        }
        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        return cpuDelta * 100.0 / (elapsedNanos * processors);
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (var value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double min(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double min = Double.POSITIVE_INFINITY;
        for (var value : values) {
            min = Math.min(min, value);
        }
        return min;
    }

    private static double max(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double max = Double.NEGATIVE_INFINITY;
        for (var value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        var sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
    }

    private static double stddev(List<Double> values, double mean) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (var value : values) {
            double delta = value - mean;
            sum += delta * delta;
        }
        return Math.sqrt(sum / values.size());
    }
}
