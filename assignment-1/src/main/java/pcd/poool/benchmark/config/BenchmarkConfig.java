package pcd.poool.benchmark;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Unified configuration model used by all benchmark runners.
 *
 * <p>The configuration keeps benchmark parameters in one place so runners do
 * not need to duplicate default values or validation rules.
 *
 * @param implementation execution strategy to benchmark
 * @param balls number of balls in the simulated workload
 * @param threads requested worker count
 * @param steps number of simulated steps
 * @param seed random seed used to build deterministic scenarios
 * @param warmupRuns number of warmup runs before measurement
 * @param measuredRuns number of measured runs
 * @param guiEnabled whether the benchmark includes GUI rendering
 * @param instrumentationEnabled whether additional profiling is enabled
 * @param outputDir directory used to export benchmark outputs
 */
public record BenchmarkConfig(
        ImplementationType implementation,
        int balls,
        int threads,
        int steps,
        long seed,
        int warmupRuns,
        int measuredRuns,
        boolean guiEnabled,
        boolean instrumentationEnabled,
        Path outputDir) {

    public static final int DEFAULT_BALLS = 100;
    public static final int DEFAULT_THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());
    public static final int DEFAULT_STEPS = 600;
    public static final long DEFAULT_SEED = 0L;
    public static final int DEFAULT_WARMUP_RUNS = 2;
    public static final int DEFAULT_MEASURED_RUNS = 5;
    public static final boolean DEFAULT_GUI_ENABLED = false;
    public static final boolean DEFAULT_INSTRUMENTATION_ENABLED = false;
    public static final Path DEFAULT_OUTPUT_DIR = Path.of("target", "benchmark-results");

    private static final int[] WORKER_MATRIX_CANDIDATES = {1, 2, 4, 8};

    private static final int PHYSICS_BALLS = 4_500;
    private static final int SEQUENTIAL_GAME_STEPS = 600;
    private static final int PHYSICS_PROFILING_STEPS = 120;
    private static final int COMPLETE_BENCHMARK_STEPS = 30;
    private static final int COMPLETE_BENCHMARK_WARMUP = 5;
    private static final int COMPLETE_BENCHMARK_REPEATS = 2;
    private static final int TASK_VS_THREADED_STEPS = 600;
    private static final int TASK_VS_THREADED_WARMUP = 50;
    private static final int TASK_VS_THREADED_REPEATS = 5;
    private static final int TASK_PROFILING_STEPS = 30;
    private static final int TASK_PROFILING_WARMUP = 5;
    private static final int TASK_PROFILING_REPEATS = 2;
    private static final int PROFILE_BALLS = 1_600;

    /**
     * Creates a default headless benchmark configuration.
     *
     * @return default benchmark configuration
     */
    public static BenchmarkConfig defaults() {
        return new BenchmarkConfig(
                ImplementationType.SEQUENTIAL,
                DEFAULT_BALLS,
                DEFAULT_THREADS,
                DEFAULT_STEPS,
                DEFAULT_SEED,
                DEFAULT_WARMUP_RUNS,
                DEFAULT_MEASURED_RUNS,
                DEFAULT_GUI_ENABLED,
                DEFAULT_INSTRUMENTATION_ENABLED,
                DEFAULT_OUTPUT_DIR);
    }

    /**
     * Returns the default benchmark matrix used by the report-oriented headless
     * comparisons.
     *
     * @return list of comparable benchmark configurations
     */
    public static List<BenchmarkConfig> defaultMatrix() {
        var configs = new ArrayList<BenchmarkConfig>();
        for (var balls : List.of(100, 500, 1_000, 2_000, 2_500)) {
            configs.add(new BenchmarkConfig(
                    ImplementationType.SEQUENTIAL,
                    balls,
                    1,
                    DEFAULT_STEPS,
                    DEFAULT_SEED,
                    DEFAULT_WARMUP_RUNS,
                    DEFAULT_MEASURED_RUNS,
                    false,
                    false,
                    DEFAULT_OUTPUT_DIR));
            for (var implementation : List.of(ImplementationType.THREADS, ImplementationType.EXECUTOR)) {
                for (var threads : workerMatrix()) {
                    configs.add(new BenchmarkConfig(
                            implementation,
                            balls,
                            threads,
                            DEFAULT_STEPS,
                            DEFAULT_SEED,
                            DEFAULT_WARMUP_RUNS,
                            DEFAULT_MEASURED_RUNS,
                            false,
                            false,
                            DEFAULT_OUTPUT_DIR));
                }
            }
        }
        return List.copyOf(configs);
    }

    /**
     * Default configuration for the standalone sequential physics benchmark.
     *
     * @return benchmark configuration
     */
    public static BenchmarkConfig physicsBenchmarkDefaults() {
        return defaults()
                .withImplementation(ImplementationType.SEQUENTIAL)
                .withBalls(PHYSICS_BALLS)
                .withMeasuredRuns(1)
                .withWarmupRuns(0);
    }

    /**
     * Default configuration for the sequential gameplay benchmark.
     *
     * @return benchmark configuration
     */
    public static BenchmarkConfig sequentialGameDefaults() {
        return defaults()
                .withImplementation(ImplementationType.SEQUENTIAL)
                .withBalls(DEFAULT_BALLS)
                .withSteps(SEQUENTIAL_GAME_STEPS)
                .withMeasuredRuns(1)
                .withWarmupRuns(0);
    }

    /**
     * Default configuration for the threaded physics benchmark.
     *
     * @return benchmark configuration
     */
    public static BenchmarkConfig threadedPhysicsDefaults() {
        return defaults()
                .withImplementation(ImplementationType.THREADS)
                .withBalls(PHYSICS_BALLS)
                .withThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))
                .withMeasuredRuns(1)
                .withWarmupRuns(0);
    }

    /**
     * Default configuration for the task-based physics benchmark.
     *
     * @return benchmark configuration
     */
    public static BenchmarkConfig taskBasedPhysicsDefaults() {
        return defaults()
                .withImplementation(ImplementationType.EXECUTOR)
                .withBalls(PHYSICS_BALLS)
                .withSteps(TASK_VS_THREADED_STEPS)
                .withWarmupRuns(TASK_VS_THREADED_WARMUP)
                .withMeasuredRuns(TASK_VS_THREADED_REPEATS);
    }

    /**
     * Default configuration for the task-vs-threaded comparison benchmark.
     *
     * @return benchmark configuration
     */
    public static BenchmarkConfig taskVsThreadedDefaults() {
        return defaults()
                .withImplementation(ImplementationType.THREADS)
                .withBalls(PHYSICS_BALLS)
                .withSteps(TASK_VS_THREADED_STEPS)
                .withWarmupRuns(TASK_VS_THREADED_WARMUP)
                .withMeasuredRuns(TASK_VS_THREADED_REPEATS);
    }

    /**
     * Default configuration for the complete comparison benchmark.
     *
     * @return benchmark configuration
     */
    public static BenchmarkConfig completeComparisonDefaults() {
        return defaults()
                .withImplementation(ImplementationType.SEQUENTIAL)
                .withBalls(DEFAULT_BALLS)
                .withSteps(COMPLETE_BENCHMARK_STEPS)
                .withWarmupRuns(COMPLETE_BENCHMARK_WARMUP)
                .withMeasuredRuns(COMPLETE_BENCHMARK_REPEATS);
    }

    /**
     * Default configuration for the threaded profiling benchmark.
     *
     * @return benchmark configuration
     */
    public static BenchmarkConfig threadedProfilingDefaults() {
        return defaults()
                .withImplementation(ImplementationType.THREADS)
                .withBalls(PROFILE_BALLS)
                .withSteps(PHYSICS_PROFILING_STEPS)
                .withMeasuredRuns(1)
                .withWarmupRuns(0);
    }

    /**
     * Default configuration for the task-based profiling benchmark.
     *
     * @return benchmark configuration
     */
    public static BenchmarkConfig taskProfilingDefaults() {
        return defaults()
                .withImplementation(ImplementationType.EXECUTOR)
                .withBalls(PROFILE_BALLS)
                .withSteps(TASK_PROFILING_STEPS)
                .withMeasuredRuns(TASK_PROFILING_REPEATS)
                .withWarmupRuns(TASK_PROFILING_WARMUP);
    }

    /**
     * Default configuration for the headless simulation runner.
     *
     * @return benchmark configuration
     */
    public static BenchmarkConfig headlessSimulationDefaults() {
        return defaults();
    }

    public BenchmarkConfig {
        if (implementation == null) {
            throw new IllegalArgumentException("implementation must not be null");
        }
        if (balls <= 0) {
            throw new IllegalArgumentException("balls must be > 0");
        }
        if (threads <= 0) {
            throw new IllegalArgumentException("threads must be > 0");
        }
        if (steps <= 0) {
            throw new IllegalArgumentException("steps must be > 0");
        }
        if (warmupRuns < 0) {
            throw new IllegalArgumentException("warmupRuns must be >= 0");
        }
        if (measuredRuns <= 0) {
            throw new IllegalArgumentException("measuredRuns must be > 0");
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir must not be null");
        }
    }

    /**
     * Returns the effective worker count for the selected implementation.
     *
     * @return worker count actually used by the runtime
     */
    public int effectiveThreads() {
        return implementation == ImplementationType.SEQUENTIAL ? 1 : threads;
    }

    /**
     * Produces a compact machine-readable representation of the configuration.
     *
     * @return config fields formatted as key=value pairs
     */
    public String toKeyValueString() {
        return String.format(Locale.US,
                "implementation=%s balls=%d threads=%d steps=%d seed=%d warmup_runs=%d measured_runs=%d gui_enabled=%s instrumentation_enabled=%s output_dir=%s",
                implementation.name().toLowerCase(Locale.ROOT),
                balls,
                threads,
                steps,
                seed,
                warmupRuns,
                measuredRuns,
                guiEnabled,
                instrumentationEnabled,
                outputDir);
    }

    /**
     * Creates a copy with a different implementation.
     *
     * @param value execution strategy
     * @return updated configuration
     */
    public BenchmarkConfig withImplementation(ImplementationType value) {
        return new BenchmarkConfig(value, balls, threads, steps, seed, warmupRuns, measuredRuns, guiEnabled, instrumentationEnabled, outputDir);
    }

    /**
     * Creates a copy with a different ball count.
     *
     * @param value ball count
     * @return updated configuration
     */
    public BenchmarkConfig withBalls(int value) {
        return new BenchmarkConfig(implementation, value, threads, steps, seed, warmupRuns, measuredRuns, guiEnabled, instrumentationEnabled, outputDir);
    }

    /**
     * Creates a copy with a different thread count.
     *
     * @param value thread count
     * @return updated configuration
     */
    public BenchmarkConfig withThreads(int value) {
        return new BenchmarkConfig(implementation, balls, value, steps, seed, warmupRuns, measuredRuns, guiEnabled, instrumentationEnabled, outputDir);
    }

    /**
     * Creates a copy with a different step count.
     *
     * @param value step count
     * @return updated configuration
     */
    public BenchmarkConfig withSteps(int value) {
        return new BenchmarkConfig(implementation, balls, threads, value, seed, warmupRuns, measuredRuns, guiEnabled, instrumentationEnabled, outputDir);
    }

    /**
     * Creates a copy with a different seed.
     *
     * @param value random seed
     * @return updated configuration
     */
    public BenchmarkConfig withSeed(long value) {
        return new BenchmarkConfig(implementation, balls, threads, steps, value, warmupRuns, measuredRuns, guiEnabled, instrumentationEnabled, outputDir);
    }

    /**
     * Creates a copy with a different warmup count.
     *
     * @param value warmup run count
     * @return updated configuration
     */
    public BenchmarkConfig withWarmupRuns(int value) {
        return new BenchmarkConfig(implementation, balls, threads, steps, seed, value, measuredRuns, guiEnabled, instrumentationEnabled, outputDir);
    }

    /**
     * Creates a copy with a different measured-run count.
     *
     * @param value measured run count
     * @return updated configuration
     */
    public BenchmarkConfig withMeasuredRuns(int value) {
        return new BenchmarkConfig(implementation, balls, threads, steps, seed, warmupRuns, value, guiEnabled, instrumentationEnabled, outputDir);
    }

    /**
     * Creates a copy with a different GUI flag.
     *
     * @param value whether GUI rendering is enabled
     * @return updated configuration
     */
    public BenchmarkConfig withGuiEnabled(boolean value) {
        return new BenchmarkConfig(implementation, balls, threads, steps, seed, warmupRuns, measuredRuns, value, instrumentationEnabled, outputDir);
    }

    /**
     * Creates a copy with a different instrumentation flag.
     *
     * @param value whether instrumentation is enabled
     * @return updated configuration
     */
    public BenchmarkConfig withInstrumentationEnabled(boolean value) {
        return new BenchmarkConfig(implementation, balls, threads, steps, seed, warmupRuns, measuredRuns, guiEnabled, value, outputDir);
    }

    /**
     * Creates a copy with a different output directory.
     *
     * @param value export directory
     * @return updated configuration
     */
    public BenchmarkConfig withOutputDir(Path value) {
        return new BenchmarkConfig(implementation, balls, threads, steps, seed, warmupRuns, measuredRuns, guiEnabled, instrumentationEnabled, value);
    }

    /**
     * Returns the resolved worker-count matrix used for parallel benchmark runs.
     *
     * <p>The matrix includes the common small counts plus the machine-specific
     * counts resolved at runtime, with duplicates removed and invalid counts
     * filtered out.
     *
     * @return resolved worker-count matrix
     */
    public static List<Integer> workerMatrix() {
        int available = Runtime.getRuntime().availableProcessors();
        var threads = new java.util.LinkedHashSet<Integer>();
        for (int candidate : WORKER_MATRIX_CANDIDATES) {
            addWorkerCount(threads, candidate);
        }
        addWorkerCount(threads, available);
        addWorkerCount(threads, available + 1);
        return List.copyOf(threads);
    }

    private static void addWorkerCount(java.util.LinkedHashSet<Integer> threads, int candidate) {
        if (candidate > 0) {
            threads.add(candidate);
        }
    }

    /**
     * Supported benchmark execution strategies.
     */
    public enum ImplementationType {
        SEQUENTIAL,
        THREADS,
        EXECUTOR;

        /**
         * Parses a textual implementation name.
         *
         * @param value implementation token
         * @return parsed implementation type
         */
        public static ImplementationType parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("implementation must not be null");
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "sequential", "seq" -> SEQUENTIAL;
                case "threads", "threaded", "thread" -> THREADS;
                case "executor", "task", "taskbased" -> EXECUTOR;
                default -> throw new IllegalArgumentException("unknown implementation: " + value);
            };
        }
    }
}
