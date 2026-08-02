package pcd.poool.benchmark.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import pcd.poool.benchmark.config.BenchmarkConfig;
import pcd.poool.benchmark.core.BenchmarkRunResult;
import pcd.poool.benchmark.core.BenchmarkRunner;
import pcd.poool.benchmark.core.RuntimeTelemetry;
import pcd.poool.benchmark.core.SeededBenchmarkBoardConf;
import pcd.poool.benchmark.engine.BenchmarkEngineAdapter;
import pcd.poool.benchmark.engine.BenchmarkEngineAdapters;
import pcd.poool.benchmark.io.RuntimeTelemetryCsvWriter;
import pcd.poool.benchmark.io.ScalabilityBenchmarkCsvWriter;
import pcd.poool.benchmark.postprocess.ScalabilityBenchmarkResultsPostProcessor;
import pcd.poool.benchmark.util.BenchmarkScenarioLogging;
import pcd.poool.model.physics.common.Board;

/**
 * Dedicated benchmark for comparing worker-count scalability of the
 * concurrent physics implementations.
 */
public final class ScalabilityBenchmarkRunner {

    private static final List<Integer> DEFAULT_WORKERS = BenchmarkConfig.workerMatrix();
    private static final List<Integer> DEFAULT_BALLS = List.of(2_500);
    private static final List<BenchmarkConfig.ImplementationType> DEFAULT_IMPLEMENTATIONS =
            List.of(
                    BenchmarkConfig.ImplementationType.SEQUENTIAL,
                    BenchmarkConfig.ImplementationType.THREADS,
                    BenchmarkConfig.ImplementationType.EXECUTOR);
    private static final int DEFAULT_STEPS = 1_000;
    private static final long DEFAULT_SEED = 42L;
    private static final int DEFAULT_WARMUP_RUNS = 2;
    private static final int DEFAULT_MEASURED_RUNS = 5;
    private static final Path DEFAULT_OUTPUT_FILE = defaultAssignmentPath("benchmarks", "results", "raw-scalability-results.csv");
    private static volatile long blackhole;

    private ScalabilityBenchmarkRunner() {
    }

    /**
     * Runs the scalability benchmark from the command line.
     *
     * @param args optional CLI arguments
     */
    public static void main(String[] args) {
        try {
            var request = parseArgs(args);
            if (request == null) {
                return;
            }
            var report = run(request);
            System.out.printf(Locale.US,
                    "scalability_benchmark_completed output=%s aggregated=%s rows=%d implementations=%d balls=%d worker_counts=%d%n",
                    report.outputFile(),
                    report.aggregatedOutputFile(),
                    report.rows().size(),
                    request.implementations().size(),
                    request.balls().size(),
                    request.workerCounts().size());
        } catch (Exception ex) {
            System.err.printf(Locale.US, "scalability_benchmark_failed message=%s%n", ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Runs the benchmark and writes raw and aggregated CSV outputs.
     *
     * @param request benchmark request
     * @return execution report
     * @throws IOException if writing fails
     */
    public static BenchmarkReport run(BenchmarkRequest request) throws IOException {
        var telemetry = RuntimeTelemetry.capture();
        var rawResults = new ArrayList<BenchmarkRunResult>();
        var rows = new ArrayList<BenchmarkRow>();
        Path outputDir = request.outputFile().getParent() == null ? Path.of(".") : request.outputFile().getParent();
        RuntimeTelemetryCsvWriter.export(outputDir, telemetry);

        for (int ballCount : request.balls()) {
            for (BenchmarkConfig.ImplementationType implementation : request.implementations()) {
                if (implementation == BenchmarkConfig.ImplementationType.SEQUENTIAL) {
                    var config = new BenchmarkConfig(
                            implementation,
                            ballCount,
                            1,
                            request.steps(),
                            request.seed(),
                            request.warmupRuns(),
                            request.measuredRuns(),
                            false,
                            false,
                            outputDir);

                    BenchmarkScenarioLogging.printScenarioStart(config);
                    var scenarioResults = runScenario(config);
                    rawResults.addAll(scenarioResults);
                    var measuredResults = scenarioResults.stream()
                            .filter(result -> !result.warmup())
                            .toList();
                    for (int runIndex = 1; runIndex <= measuredResults.size(); runIndex++) {
                        var result = measuredResults.get(runIndex - 1);
                        if (result.failed()) {
                            throw new IllegalStateException("benchmark run failed: " + result.failureMessage());
                        }
                        var row = toRow(result, telemetry, config, runIndex);
                        rows.add(row);
                    }
                    BenchmarkScenarioLogging.printScenarioDone(config, request.measuredRuns());
                    continue;
                }

                for (int workers : request.workerCounts()) {
                    var config = new BenchmarkConfig(
                            implementation,
                            ballCount,
                            workers,
                            request.steps(),
                            request.seed(),
                            request.warmupRuns(),
                            request.measuredRuns(),
                            false,
                            false,
                            outputDir);

                    BenchmarkScenarioLogging.printScenarioStart(config);
                    var scenarioResults = runScenario(config);
                    rawResults.addAll(scenarioResults);
                    var measuredResults = scenarioResults.stream()
                            .filter(result -> !result.warmup())
                            .toList();
                    for (int runIndex = 1; runIndex <= measuredResults.size(); runIndex++) {
                        var result = measuredResults.get(runIndex - 1);
                        if (result.failed()) {
                            throw new IllegalStateException("benchmark run failed: " + result.failureMessage());
                        }
                        var row = toRow(result, telemetry, config, runIndex);
                        rows.add(row);
                    }
                    BenchmarkScenarioLogging.printScenarioDone(config, request.measuredRuns());
                }
            }
        }

        ScalabilityBenchmarkCsvWriter.write(request.outputFile(), rows);
        var derived = ScalabilityBenchmarkResultsPostProcessor.process(request.outputFile());
        return new BenchmarkReport(request.outputFile(), derived.aggregatedFile(), List.copyOf(rawResults), List.copyOf(rows));
    }

    /**
     * Creates the default scalability request.
     *
     * @return default scalability request
     */
    public static BenchmarkRequest defaults() {
        return new BenchmarkRequest(
                DEFAULT_IMPLEMENTATIONS,
                DEFAULT_BALLS,
                DEFAULT_WORKERS,
                DEFAULT_STEPS,
                DEFAULT_SEED,
                DEFAULT_WARMUP_RUNS,
                DEFAULT_MEASURED_RUNS,
                DEFAULT_OUTPUT_FILE);
    }

    private static List<BenchmarkRunResult> runScenario(BenchmarkConfig config) {
        var results = new ArrayList<BenchmarkRunResult>(config.warmupRuns() + config.measuredRuns());
        for (int i = 0; i < config.warmupRuns(); i++) {
            var result = measureRun(config, i + 1, true);
            results.add(result);
            if (result.failed()) {
                throw new IllegalStateException("warmup run failed: " + result.failureMessage());
            }
        }
        for (int i = 0; i < config.measuredRuns(); i++) {
            var result = measureRun(config, i + 1, false);
            results.add(result);
            if (result.failed()) {
                throw new IllegalStateException("benchmark run failed: " + result.failureMessage());
            }
        }
        return List.copyOf(results);
    }

    private static BenchmarkRunResult measureRun(BenchmarkConfig config, int runIndex, boolean warmup) {
        BenchmarkEngineAdapter adapter = BenchmarkEngineAdapters.forImplementation(config.implementation(), config.effectiveThreads());
        try (BenchmarkEngineAdapter.BenchmarkEngineSession session = adapter.open()) {
            var board = new Board(session.stepper());
            board.init(new SeededBenchmarkBoardConf(config.balls(), config.seed()));
            var result = BenchmarkRunner.time(runIndex, warmup, config.steps(), () ->
                    session.execute(board, config.steps(), config.instrumentationEnabled()));
            blackhole = result.checksum();
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to measure benchmark run", ex);
        }
    }

    private static BenchmarkRow toRow(
            BenchmarkRunResult result,
            RuntimeTelemetry telemetry,
            BenchmarkConfig config,
            int runIndex) {
        String jvm = telemetry.jvmName() + " " + telemetry.jvmVersion();
        String os = telemetry.osName() + " " + telemetry.osVersion() + " " + telemetry.osArch();
        return new BenchmarkRow(
                config.implementation().name().toLowerCase(Locale.ROOT),
                config.balls(),
                config.effectiveThreads(),
                config.steps(),
                config.seed(),
                runIndex,
                false,
                result.elapsedMillis(),
                result.throughputStepsPerSecond(),
                coordinationMs(result),
                coordinationRatio(result),
                tasksSubmitted(result),
                jvm,
                os,
                telemetry.maxThreads());
    }

    private static BenchmarkRequest parseArgs(String[] args) {
        var request = defaults();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                return null;
            }
            String key;
            String value;
            int equals = arg.indexOf('=');
            if (equals >= 0) {
                key = arg.substring(0, equals);
                value = arg.substring(equals + 1);
            } else {
                key = arg;
                if (isFlagWithoutValue(key)) {
                    continue;
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + key);
                }
                value = args[++i];
            }

            switch (key) {
                case "--implementation", "-i" -> request = request.withImplementations(parseImplementations(value));
                case "--balls", "-b" -> request = request.withBalls(parseBalls(value));
                case "--workers", "-w" -> request = request.withWorkerCounts(parseWorkers(value));
                case "--steps", "-s" -> request = request.withSteps(Integer.parseInt(value));
                case "--seed" -> request = request.withSeed(Long.parseLong(value));
                case "--warmup" -> request = request.withWarmupRuns(Integer.parseInt(value));
                case "--measured", "-m" -> request = request.withMeasuredRuns(Integer.parseInt(value));
                case "--output", "-o" -> request = request.withOutputFile(Path.of(value));
                default -> throw new IllegalArgumentException("unknown option: " + key);
            }
        }
        return request;
    }

    private static boolean isFlagWithoutValue(String key) {
        return "--help".equals(key) || "-h".equals(key);
    }

    private static List<Integer> parseWorkers(String value) {
        return parseInts(value);
    }

    private static List<Integer> parseBalls(String value) {
        return parseInts(value);
    }

    private static List<Integer> parseInts(String value) {
        var values = new ArrayList<Integer>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                values.add(Integer.parseInt(trimmed));
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("value list must not be empty");
        }
        return List.copyOf(values);
    }

    private static List<BenchmarkConfig.ImplementationType> parseImplementations(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> DEFAULT_IMPLEMENTATIONS;
            case "threads", "threaded", "thread" -> List.of(BenchmarkConfig.ImplementationType.THREADS);
            case "executor", "task", "taskbased" -> List.of(BenchmarkConfig.ImplementationType.EXECUTOR);
            default -> throw new IllegalArgumentException("unknown implementation: " + value);
        };
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java -cp assignment-1/target/classes pcd.poool.benchmark.ScalabilityBenchmarkRunner \
                  [--implementation threads|executor|all] \
                  [--balls 2500] \
                  [--workers 1,2,4,8,...] \
                  [--steps N] \
                  [--seed N] \
                  [--warmup N] \
                  [--measured N] \
                  [--output benchmarks/results/raw-scalability-results.csv]
                """);
    }

    private static Path defaultAssignmentPath(String... segments) {
        Path assignmentRoot = Path.of("assignment-1");
        if (Files.isDirectory(assignmentRoot)) {
            return assignmentRoot.resolve(Path.of("", segments));
        }
        return Path.of("", segments);
    }

    /**
     * Benchmark request parameters.
     *
     * @param implementations implementation types to compare
     * @param balls ball counts to benchmark
     * @param workerCounts worker counts to use
     * @param steps simulation steps per run
     * @param seed random seed
     * @param warmupRuns number of warmup runs
     * @param measuredRuns number of measured runs
     * @param outputFile CSV output path
     */
    public record BenchmarkRequest(
            List<BenchmarkConfig.ImplementationType> implementations,
            List<Integer> balls,
            List<Integer> workerCounts,
            int steps,
            long seed,
            int warmupRuns,
            int measuredRuns,
            Path outputFile) {

        public BenchmarkRequest {
            if (implementations == null || implementations.isEmpty()) {
                throw new IllegalArgumentException("implementations must not be empty");
            }
            implementations = List.copyOf(implementations);
            if (balls == null || balls.isEmpty()) {
                throw new IllegalArgumentException("balls must not be empty");
            }
            balls = List.copyOf(balls);
            if (workerCounts == null || workerCounts.isEmpty()) {
                throw new IllegalArgumentException("workerCounts must not be empty");
            }
            workerCounts = List.copyOf(workerCounts);
            for (int ballCount : balls) {
                if (ballCount <= 0) {
                    throw new IllegalArgumentException("balls must be > 0");
                }
            }
            for (int workers : workerCounts) {
                if (workers <= 0) {
                    throw new IllegalArgumentException("workerCounts must be > 0");
                }
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
            if (outputFile == null) {
                throw new IllegalArgumentException("outputFile must not be null");
            }
        }

        public BenchmarkRequest withImplementations(List<BenchmarkConfig.ImplementationType> value) {
            return new BenchmarkRequest(value, balls, workerCounts, steps, seed, warmupRuns, measuredRuns, outputFile);
        }

        public BenchmarkRequest withBalls(List<Integer> value) {
            return new BenchmarkRequest(implementations, value, workerCounts, steps, seed, warmupRuns, measuredRuns, outputFile);
        }

        public BenchmarkRequest withWorkerCounts(List<Integer> value) {
            return new BenchmarkRequest(implementations, balls, value, steps, seed, warmupRuns, measuredRuns, outputFile);
        }

        public BenchmarkRequest withSteps(int value) {
            return new BenchmarkRequest(implementations, balls, workerCounts, value, seed, warmupRuns, measuredRuns, outputFile);
        }

        public BenchmarkRequest withSeed(long value) {
            return new BenchmarkRequest(implementations, balls, workerCounts, steps, value, warmupRuns, measuredRuns, outputFile);
        }

        public BenchmarkRequest withWarmupRuns(int value) {
            return new BenchmarkRequest(implementations, balls, workerCounts, steps, seed, value, measuredRuns, outputFile);
        }

        public BenchmarkRequest withMeasuredRuns(int value) {
            return new BenchmarkRequest(implementations, balls, workerCounts, steps, seed, warmupRuns, value, outputFile);
        }

        public BenchmarkRequest withOutputFile(Path value) {
            return new BenchmarkRequest(implementations, balls, workerCounts, steps, seed, warmupRuns, measuredRuns, value);
        }
    }

    /**
     * Report produced by the scalability benchmark runner.
     *
     * @param outputFile raw CSV output path
     * @param aggregatedOutputFile aggregated CSV output path
     * @param rows measured benchmark rows
     */
    public record BenchmarkReport(Path outputFile, Path aggregatedOutputFile, List<BenchmarkRunResult> rawResults, List<BenchmarkRow> rows) {
    }

    /**
     * Single measured benchmark row.
     *
     * @param implementation implementation name
     * @param balls ball count
     * @param workers worker count used by the implementation
     * @param steps simulation steps
     * @param seed random seed
     * @param runIndex measured run index
     * @param warmup whether the row represents a warmup run
     * @param elapsedMs measured elapsed time in milliseconds
     * @param throughput steps per second
     * @param coordinationMs estimated coordination time in milliseconds
     * @param coordinationRatio coordination time divided by elapsed time
     * @param tasksSubmitted tasks submitted during the run
     * @param jvm JVM identification string
     * @param os operating system identification string
     * @param availableProcessors available CPU count
     */
    public record BenchmarkRow(
            String implementation,
            int balls,
            int workers,
            int steps,
            long seed,
            int runIndex,
            boolean warmup,
            double elapsedMs,
            double throughput,
            double coordinationMs,
            double coordinationRatio,
            long tasksSubmitted,
            String jvm,
            String os,
            int availableProcessors) {
    }

    private static double coordinationMs(BenchmarkRunResult result) {
        return result.instrumentation().syncTimeMillis();
    }

    private static double coordinationRatio(BenchmarkRunResult result) {
        return result.elapsedMillis() <= 0.0 ? 0.0 : coordinationMs(result) / result.elapsedMillis();
    }

    private static long tasksSubmitted(BenchmarkRunResult result) {
        return result.instrumentation().submittedTasks();
    }
}
