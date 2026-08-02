package pcd.poool.benchmark.runner;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import pcd.poool.benchmark.config.BenchmarkConfig;
import pcd.poool.benchmark.core.BenchmarkRunResult;
import pcd.poool.benchmark.core.BenchmarkRunner;
import pcd.poool.benchmark.core.BenchmarkSummary;
import pcd.poool.benchmark.core.RuntimeTelemetry;
import pcd.poool.benchmark.io.BenchmarkCsvWriter;
import pcd.poool.benchmark.io.RuntimeTelemetryCsvWriter;
import pcd.poool.benchmark.postprocess.BenchmarkResultsExporter;
import pcd.poool.benchmark.engine.BenchmarkCorrectnessGuard;

/**
 * Executes the full benchmark matrix and exports the results.
 */
public final class BenchmarkSuite {

    private static final List<Integer> BALL_COUNTS = List.of(100, 500, 1_000, 1_500, 2_000, 2_500);
    private static final List<Integer> THREAD_COUNTS = BenchmarkConfig.workerMatrix();
    private static final List<Integer> CI_SMOKE_THREAD_COUNTS = List.of(1, 2);
    private static final int CI_SMOKE_BALLS = 100;
    private static final int CI_SMOKE_STEPS = 1_000;
    private static final int CI_SMOKE_WARMUP = 1;
    private static final int CI_SMOKE_MEASURED = 1;
    private static final Path DEFAULT_RESULTS_ROOT = Path.of("assignment-1", "benchmarks", "results");
    private static final Path CI_RESULTS_ROOT = DEFAULT_RESULTS_ROOT;

    private BenchmarkSuite() {
    }

    /**
     * Runs the complete benchmark matrix.
     *
     * @param args optional benchmark results root directory
     */
    public static void main(String[] args) {
        Mode mode = Mode.FULL;
        int argIndex = 0;
        if (args.length > 0 && isModeToken(args[0])) {
            mode = Mode.parse(args[0]);
            argIndex = 1;
        }
        Path resultsRoot = args.length > argIndex
                ? Path.of(args[argIndex])
                : mode == Mode.SMOKE ? CI_RESULTS_ROOT : DEFAULT_RESULTS_ROOT;
        try {
            var report = run(resultsRoot, System.out, System.err, mode);
            System.out.printf(Locale.US,
                    "suite_completed output_dir=%s configs=%d failed_configs=%d%n",
                    report.outputDir(),
                    report.completedConfigs(),
                    report.failedConfigs());
        } catch (Exception ex) {
            System.err.printf(Locale.US, "suite_failed message=%s%n", ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Runs the suite using the default headless benchmark workload.
     *
     * @param resultsRoot root directory that will contain the latest run
     * @param out progress stream
     * @param err error stream
     * @return suite execution report
     * @throws Exception if directory creation fails
     */
    public static SuiteReport run(Path resultsRoot, PrintStream out, PrintStream err) throws Exception {
        return run(resultsRoot, out, err, Mode.FULL);
    }

    /**
     * Runs the suite in the requested mode using the default headless
     * benchmark workload.
     *
     * @param resultsRoot root directory that will contain the latest run
     * @param out progress stream
     * @param err error stream
     * @param mode suite execution mode
     * @return suite execution report
     * @throws Exception if directory creation fails
     */
    public static SuiteReport run(Path resultsRoot, PrintStream out, PrintStream err, Mode mode) throws Exception {
        var correctnessGuard = new BenchmarkCorrectnessGuard();
        return run(
                mode == Mode.SMOKE ? buildSmokeMatrix(resultsRoot) : buildMatrix(resultsRoot),
                config -> correctnessGuard.wrap(config, () -> HeadlessSimulationRunner.simulateExecution(config)),
                out,
                err);
    }

    /**
     * Runs the suite with a custom workload factory, which is useful for
     * testing failure handling.
     *
     * @param configs benchmark configurations
     * @param workloadFactory factory that builds the timed workload for each config
     * @param out progress stream
     * @param err error stream
     * @return suite execution report
     * @throws Exception if directory creation or export fails unexpectedly
     */
    public static SuiteReport run(
            List<BenchmarkConfig> configs,
            WorkloadFactory workloadFactory,
            PrintStream out,
            PrintStream err) throws Exception {
        Objects.requireNonNull(configs, "configs");
        Objects.requireNonNull(workloadFactory, "workloadFactory");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        if (configs.isEmpty()) {
            throw new IllegalArgumentException("configs must not be empty");
        }

        Path outputDir = configs.get(0).outputDir();
        Files.createDirectories(outputDir);
        RuntimeTelemetry telemetry = RuntimeTelemetry.capture();
        RuntimeTelemetryCsvWriter.export(outputDir, telemetry);

        Map<ScenarioKey, BenchmarkSummary> sequentialBaselines = new LinkedHashMap<>();
        var summaries = new ArrayList<BenchmarkSummary>();
        int completedScenarios = 0;
        int failedScenarios = 0;
        int scenarioCount = configs.size();

        for (int scenarioIndex = 0; scenarioIndex < configs.size(); scenarioIndex++) {
            var config = configs.get(scenarioIndex);
            int currentScenario = scenarioIndex + 1;
            printScenarioStart(out, currentScenario, scenarioCount, config);

            BenchmarkRunner.BenchmarkExecutionWorkload workload;
            try {
                workload = workloadFactory.create(config);
            } catch (Exception ex) {
                failedScenarios++;
                err.printf(Locale.US,
                        "benchmark_scenario_failed implementation=%s balls=%d threads=%d steps=%d message=%s%n",
                        config.implementation().name().toLowerCase(Locale.ROOT),
                        config.balls(),
                        config.threads(),
                        config.steps(),
                        ex.getMessage());
                continue;
            }

            var rawResults = runScenario(config, workload, out);
            var summary = BenchmarkRunner.summarize(config, rawResults);
            summaries.add(summary);
            if (summary.config().implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL) {
                sequentialBaselines.put(new ScenarioKey(config.balls(), config.steps(), config.seed()), summary);
            }

            BenchmarkSummary sequentialBaseline = sequentialBaselines.getOrDefault(
                    new ScenarioKey(config.balls(), config.steps(), config.seed()),
                    summary.config().implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL ? summary : null);

            if (sequentialBaseline == null) {
                err.printf(Locale.US,
                        "benchmark_export_skipped reason=missing_sequential_baseline implementation=%s balls=%d threads=%d steps=%d%n",
                        config.implementation().name().toLowerCase(Locale.ROOT),
                        config.balls(),
                        config.threads(),
                        config.steps());
            } else {
                try {
                    if (config.implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL) {
                        BenchmarkCsvWriter.export(config, rawResults, summary);
                    } else {
                        BenchmarkCsvWriter.export(config, rawResults, summary, sequentialBaseline);
                    }
                } catch (Exception ex) {
                    failedScenarios++;
                    err.printf(Locale.US,
                            "benchmark_export_failed implementation=%s balls=%d threads=%d steps=%d message=%s%n",
                            config.implementation().name().toLowerCase(Locale.ROOT),
                            config.balls(),
                            config.threads(),
                            config.steps(),
                            ex.getMessage());
                    continue;
                }
            }

            if (summary.failedRuns() > 0) {
                failedScenarios++;
                err.printf(Locale.US,
                        "benchmark_scenario_completed_with_failures implementation=%s balls=%d threads=%d steps=%d failed_runs=%d%n",
                        config.implementation().name().toLowerCase(Locale.ROOT),
                        config.balls(),
                        config.threads(),
                        config.steps(),
                        summary.failedRuns());
            }
            completedScenarios++;
            printScenarioCompleted(out, completedScenarios, scenarioCount, config, summary);
        }

        BenchmarkResultsExporter.export(
                outputDir,
                Instant.now(),
                telemetry,
                BenchmarkResultsExporter.resolveGitCommitHash(),
                summaries);

        return new SuiteReport(outputDir, completedScenarios, failedScenarios);
    }

    /**
     * Builds the benchmark matrix for the suite.
     *
     * @param resultsRoot root directory that will contain the latest run
     * @return benchmark configurations with a shared output directory
     * @throws Exception if the output directory cannot be created
     */
    public static List<BenchmarkConfig> buildMatrix(Path resultsRoot) throws Exception {
        Objects.requireNonNull(resultsRoot, "resultsRoot");
        Path outputDir = resultsRoot;
        Files.createDirectories(outputDir);

        var configs = new ArrayList<BenchmarkConfig>();
        for (var balls : BALL_COUNTS) {
            configs.add(baseConfig()
                    .withBalls(balls)
                    .withThreads(1)
                    .withImplementation(BenchmarkConfig.ImplementationType.SEQUENTIAL)
                    .withOutputDir(outputDir));
            for (var threads : THREAD_COUNTS) {
                configs.add(baseConfig()
                        .withBalls(balls)
                        .withThreads(threads)
                        .withImplementation(BenchmarkConfig.ImplementationType.THREADS)
                        .withOutputDir(outputDir));
            }
            for (var threads : THREAD_COUNTS) {
                configs.add(baseConfig()
                        .withBalls(balls)
                        .withThreads(threads)
                        .withImplementation(BenchmarkConfig.ImplementationType.EXECUTOR)
                        .withOutputDir(outputDir));
            }
        }
        return List.copyOf(configs);
    }

    /**
     * Builds the lightweight smoke benchmark matrix used by CI.
     *
     * @param resultsRoot root directory that will contain the latest run
     * @return benchmark configurations for the smoke suite
     * @throws Exception if the output directory cannot be created
     */
    public static List<BenchmarkConfig> buildSmokeMatrix(Path resultsRoot) throws Exception {
        Objects.requireNonNull(resultsRoot, "resultsRoot");
        Path outputDir = resultsRoot;
        Files.createDirectories(outputDir);

        var configs = new ArrayList<BenchmarkConfig>();
        configs.add(baseConfig()
                .withBalls(CI_SMOKE_BALLS)
                .withSteps(CI_SMOKE_STEPS)
                .withWarmupRuns(CI_SMOKE_WARMUP)
                .withMeasuredRuns(CI_SMOKE_MEASURED)
                .withThreads(1)
                .withImplementation(BenchmarkConfig.ImplementationType.SEQUENTIAL)
                .withOutputDir(outputDir));
        for (var threads : CI_SMOKE_THREAD_COUNTS) {
            configs.add(baseConfig()
                    .withBalls(CI_SMOKE_BALLS)
                    .withSteps(CI_SMOKE_STEPS)
                    .withWarmupRuns(CI_SMOKE_WARMUP)
                    .withMeasuredRuns(CI_SMOKE_MEASURED)
                    .withThreads(threads)
                    .withImplementation(BenchmarkConfig.ImplementationType.THREADS)
                    .withOutputDir(outputDir));
        }
        for (var threads : CI_SMOKE_THREAD_COUNTS) {
            configs.add(baseConfig()
                    .withBalls(CI_SMOKE_BALLS)
                    .withSteps(CI_SMOKE_STEPS)
                    .withWarmupRuns(CI_SMOKE_WARMUP)
                    .withMeasuredRuns(CI_SMOKE_MEASURED)
                    .withThreads(threads)
                    .withImplementation(BenchmarkConfig.ImplementationType.EXECUTOR)
                    .withOutputDir(outputDir));
        }
        return List.copyOf(configs);
    }

    private static BenchmarkConfig baseConfig() {
        return BenchmarkConfig.defaults()
                .withWarmupRuns(BenchmarkConfig.DEFAULT_WARMUP_RUNS)
                .withMeasuredRuns(BenchmarkConfig.DEFAULT_MEASURED_RUNS)
                .withGuiEnabled(false)
                .withInstrumentationEnabled(false);
    }

    private static boolean isModeToken(String value) {
        return value != null && switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "--smoke", "smoke", "--full", "full" -> true;
            default -> false;
        };
    }

    /**
     * Suite execution mode.
     */
    public enum Mode {
        FULL,
        SMOKE;

        static Mode parse(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "--smoke", "smoke" -> SMOKE;
                case "--full", "full" -> FULL;
                default -> throw new IllegalArgumentException("unknown suite mode: " + value);
            };
        }
    }

    private static List<BenchmarkRunResult> runScenario(
            BenchmarkConfig config,
            BenchmarkRunner.BenchmarkExecutionWorkload workload,
            PrintStream out) {
        var results = new ArrayList<BenchmarkRunResult>(config.warmupRuns() + config.measuredRuns());
        int totalRuns = config.warmupRuns() + config.measuredRuns();
        int runIndex = 1;

        for (int i = 0; i < config.warmupRuns(); i++) {
            printRunStart(out, config, runIndex, totalRuns, true);
            var result = BenchmarkRunner.time(runIndex, true, config.steps(), workload);
            results.add(result);
            printRunEnd(out, result);
            runIndex++;
        }

        for (int i = 0; i < config.measuredRuns(); i++) {
            printRunStart(out, config, runIndex, totalRuns, false);
            var result = BenchmarkRunner.time(runIndex, false, config.steps(), workload);
            results.add(result);
            printRunEnd(out, result);
            runIndex++;
        }

        return List.copyOf(results);
    }

    private static void printScenarioStart(PrintStream out, int current, int total, BenchmarkConfig config) {
        out.printf(Locale.US,
                "scenario_start current_scenario=%d completed_scenarios=%d total_scenarios=%d implementation=%s balls=%d threads=%d steps=%d seed=%d%n",
                current,
                current - 1,
                total,
                config.implementation().name().toLowerCase(Locale.ROOT),
                config.balls(),
                config.threads(),
                config.steps(),
                config.seed());
    }

    private static void printRunStart(PrintStream out, BenchmarkConfig config, int runIndex, int totalRuns, boolean warmup) {
        out.printf(Locale.US,
                "run_start scenario=%s balls=%d threads=%d run=%d/%d phase=%s%n",
                config.implementation().name().toLowerCase(Locale.ROOT),
                config.balls(),
                config.threads(),
                runIndex,
                totalRuns,
                warmup ? "warmup" : "measured");
    }

    private static void printRunEnd(PrintStream out, BenchmarkRunResult result) {
        out.printf(Locale.US,
                "run_end run=%d status=%s elapsed_ms=%.3f throughput=%.3f checksum=%d sync_ms=%.3f agg_ms=%.3f submit_ms=%.3f wait_ms=%.3f locks=%d tasks=%d%n",
                result.runIndex(),
                result.status(),
                result.elapsedMillis(),
                result.throughputStepsPerSecond(),
                result.checksum(),
                result.instrumentation().syncTimeMillis(),
                result.instrumentation().aggregationTimeMillis(),
                result.instrumentation().taskSubmissionTimeMillis(),
                result.instrumentation().joinOrFutureWaitMillis(),
                result.instrumentation().lockAcquisitions(),
                result.instrumentation().submittedTasks());
    }

    private static void printScenarioCompleted(
            PrintStream out,
            int completed,
            int total,
            BenchmarkConfig config,
            BenchmarkSummary summary) {
        out.printf(Locale.US,
                "scenario_completed completed_scenarios=%d/%d implementation=%s balls=%d threads=%d mean_ms=%.3f checksum=%d%n",
                completed,
                total,
                config.implementation().name().toLowerCase(Locale.ROOT),
                config.balls(),
                config.threads(),
                summary.meanElapsedMillis(),
                summary.checksum());
    }

    @FunctionalInterface
    public interface WorkloadFactory {

        BenchmarkRunner.BenchmarkExecutionWorkload create(BenchmarkConfig config);
    }

    /**
     * Immutable report for a suite execution.
     *
     * @param outputDir output directory containing the latest benchmark files
     * @param completedConfigs number of configs completed successfully
     * @param failedConfigs number of configs that failed during export
     */
    public record SuiteReport(Path outputDir, int completedConfigs, int failedConfigs) {
    }

    private record ScenarioKey(int balls, int steps, long seed) {
    }

}
