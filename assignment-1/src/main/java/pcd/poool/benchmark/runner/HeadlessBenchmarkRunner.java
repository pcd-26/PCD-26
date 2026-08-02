package pcd.poool.benchmark.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import pcd.poool.benchmark.config.BenchmarkConfig;
import pcd.poool.benchmark.core.BenchmarkInstrumentation;
import pcd.poool.benchmark.core.BenchmarkRunResult;
import pcd.poool.benchmark.core.BenchmarkRunner;
import pcd.poool.benchmark.core.RuntimeTelemetry;
import pcd.poool.benchmark.core.SeededBenchmarkBoardConf;
import pcd.poool.benchmark.engine.BenchmarkEngineAdapter;
import pcd.poool.benchmark.engine.BenchmarkEngineAdapters;
import pcd.poool.benchmark.io.HeadlessBenchmarkCsvWriter;
import pcd.poool.benchmark.io.RuntimeTelemetryCsvWriter;
import pcd.poool.benchmark.postprocess.HeadlessBenchmarkResultsPostProcessor;
import pcd.poool.benchmark.util.BenchmarkScenarioLogging;
import pcd.poool.model.physics.common.Board;

/**
 * Reproducible headless benchmark runner for the three simulation
 * implementations.
 *
 * <p>The benchmark prepares a fresh seeded board for each run, measures only
 * the simulation loop with {@link System#nanoTime()}, and writes one CSV row
 * per measured run.
 */
public final class HeadlessBenchmarkRunner {

    private static final List<Integer> DEFAULT_BALLS = List.of(100, 500, 1_000, 1_500, 2_000, 2_500);
    private static final List<Integer> SPEEDUP_GATE_BALLS = List.of(1_000, 1_500, 2_000, 2_500);
    private static final List<BenchmarkConfig.ImplementationType> DEFAULT_IMPLEMENTATIONS =
            List.of(BenchmarkConfig.ImplementationType.SEQUENTIAL,
                    BenchmarkConfig.ImplementationType.THREADS,
                    BenchmarkConfig.ImplementationType.EXECUTOR);
    private static final int DEFAULT_STEPS = 1_000;
    private static final int SPEEDUP_GATE_STEPS = BenchmarkConfig.DEFAULT_STEPS;
    private static final long DEFAULT_SEED = 42L;
    private static final long SPEEDUP_GATE_SEED = DEFAULT_SEED;
    private static final int DEFAULT_WARMUP_RUNS = 2;
    private static final int DEFAULT_MEASURED_RUNS = 5;
    private static final int SPEEDUP_GATE_WARMUP_RUNS = DEFAULT_WARMUP_RUNS;
    private static final int SPEEDUP_GATE_MEASURED_RUNS = DEFAULT_MEASURED_RUNS;
    private static final Path DEFAULT_OUTPUT_FILE = defaultAssignmentPath("benchmarks", "results", "raw-results.csv");
    private static Integer cachedWorkerCount;
    private static volatile long blackhole;

    private HeadlessBenchmarkRunner() {
    }

    /**
     * Runs the benchmark from the command line.
     *
     * <p>Supported options:
     * <ul>
     *   <li><code>--implementation all|sequential|threads|executor</code></li>
     *   <li><code>--balls 100,500,...</code></li>
     *   <li><code>--steps N</code></li>
     *   <li><code>--seed N</code></li>
     *   <li><code>--workers N</code></li>
     *   <li><code>--warmup N</code></li>
     *   <li><code>--measured N</code></li>
     *   <li><code>--output path/to/raw-results.csv</code></li>
     * </ul>
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
                    "benchmark_completed output=%s rows=%d implementations=%d balls=%d warmup_runs=%d measured_runs=%d%n",
                    report.outputFile(),
                    report.rows().size(),
                    request.implementations().size(),
                    request.balls().size(),
                    request.warmupRuns(),
                    request.measuredRuns());
        } catch (Exception ex) {
            System.err.printf(Locale.US, "benchmark_failed message=%s%n", ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Runs the benchmark matrix and writes the raw CSV output.
     *
     * @param request benchmark request
     * @return execution report including the generated rows
     * @throws IOException if writing the CSV fails
     */
    public static BenchmarkReport run(BenchmarkRequest request) throws IOException {
        var telemetry = RuntimeTelemetry.capture();
        var rawResults = new ArrayList<BenchmarkRunResult>();
        var rows = new ArrayList<BenchmarkRow>();
        Path outputDir = request.outputFile().getParent() == null ? Path.of(".") : request.outputFile().getParent();
        RuntimeTelemetryCsvWriter.export(outputDir, telemetry);

        for (int ballCount : request.balls()) {
            for (BenchmarkConfig.ImplementationType implementation : request.implementations()) {
                var config = new BenchmarkConfig(
                        implementation,
                        ballCount,
                        request.workers(),
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

        HeadlessBenchmarkCsvWriter.write(request.outputFile(), rows);
        var derived = HeadlessBenchmarkResultsPostProcessor.process(request.outputFile());
        return new BenchmarkReport(
                request.outputFile(),
                derived.aggregatedFile(),
                derived.speedupFile(),
                List.copyOf(rawResults),
                List.copyOf(rows));
    }

    /**
     * Creates the default benchmark request.
     *
     * @return default request comparing all implementations
     */
    public static BenchmarkRequest defaults() {
        return new BenchmarkRequest(
                DEFAULT_IMPLEMENTATIONS,
                DEFAULT_BALLS,
                DEFAULT_STEPS,
                DEFAULT_SEED,
                workerCount(),
                DEFAULT_WARMUP_RUNS,
                DEFAULT_MEASURED_RUNS,
                DEFAULT_OUTPUT_FILE);
    }

    /**
     * Creates the compact benchmark request used as the performance gate.
     *
     * <p>The compact matrix is intentionally small so it can be executed often
     * while still covering the larger workload sizes used to compare engine
     * speedup before and after a change. It keeps the same resolved default
     * worker count as the main headless runner so the comparison stays
     * comparable across ball sizes and across repeated runs on the same
     * machine.
     *
     * @return compact speedup-gate request
     */
    public static BenchmarkRequest speedupGateDefaults() {
        return new BenchmarkRequest(
                DEFAULT_IMPLEMENTATIONS,
                SPEEDUP_GATE_BALLS,
                SPEEDUP_GATE_STEPS,
                SPEEDUP_GATE_SEED,
                workerCount(),
                SPEEDUP_GATE_WARMUP_RUNS,
                SPEEDUP_GATE_MEASURED_RUNS,
                DEFAULT_OUTPUT_FILE);
    }

    private static int workerCount() {
        Integer value = cachedWorkerCount;
        if (value != null) {
            return value;
        }
        synchronized (HeadlessBenchmarkRunner.class) {
            if (cachedWorkerCount == null) {
                cachedWorkerCount = Math.max(1, Runtime.getRuntime().availableProcessors());
            }
            return cachedWorkerCount;
        }
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
                coordinationMillis(result),
                coordinationRatio(result),
                tasksSubmitted(result),
                result.checksum(),
                jvm,
                os,
                telemetry.maxThreads());
    }

    private static double coordinationMillis(BenchmarkRunResult result) {
        return result.instrumentation().syncTimeMillis();
    }

    private static double coordinationRatio(BenchmarkRunResult result) {
        if (result.elapsedMillis() <= 0.0) {
            return 0.0;
        }
        return coordinationMillis(result) / result.elapsedMillis();
    }

    private static long tasksSubmitted(BenchmarkRunResult result) {
        return result.instrumentation().submittedTasks();
    }

    private static List<Integer> parseBalls(String value) {
        var balls = new ArrayList<Integer>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                balls.add(Integer.parseInt(trimmed));
            }
        }
        if (balls.isEmpty()) {
            throw new IllegalArgumentException("balls must not be empty");
        }
        return List.copyOf(balls);
    }

    private static List<BenchmarkConfig.ImplementationType> parseImplementations(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> DEFAULT_IMPLEMENTATIONS;
            case "sequential", "seq" -> List.of(BenchmarkConfig.ImplementationType.SEQUENTIAL);
            case "threads", "threaded", "thread" -> List.of(BenchmarkConfig.ImplementationType.THREADS);
            case "executor", "task", "taskbased" -> List.of(BenchmarkConfig.ImplementationType.EXECUTOR);
            default -> throw new IllegalArgumentException("unknown implementation: " + value);
        };
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
                case "--steps", "-s" -> request = request.withSteps(Integer.parseInt(value));
                case "--seed" -> request = request.withSeed(Long.parseLong(value));
                case "--workers", "-w" -> request = request.withWorkers(Integer.parseInt(value));
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

    private static void printUsage() {
        System.out.println("""
                Usage: java -cp assignment-1/target/classes pcd.poool.benchmark.HeadlessBenchmarkRunner \
                  [--implementation all|sequential|threads|executor] \
                  [--balls 100,500,...] \
                  [--steps N] \
                  [--seed N] \
                  [--workers N] \
                  [--warmup N] \
                  [--measured N] \
                  [--output benchmarks/results/raw-results.csv]
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
     * @param steps simulation steps per run
     * @param seed random seed for the deterministic board
     * @param workers worker count for concurrent implementations
     * @param warmupRuns number of warmup runs
     * @param measuredRuns number of measured runs
     * @param outputFile CSV output path
     */
    public record BenchmarkRequest(
            List<BenchmarkConfig.ImplementationType> implementations,
            List<Integer> balls,
            int steps,
            long seed,
            int workers,
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
            for (int ballCount : balls) {
                if (ballCount <= 0) {
                    throw new IllegalArgumentException("balls must be > 0");
                }
            }
            if (steps <= 0) {
                throw new IllegalArgumentException("steps must be > 0");
            }
            if (workers <= 0) {
                throw new IllegalArgumentException("workers must be > 0");
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
            return new BenchmarkRequest(value, balls, steps, seed, workers, warmupRuns, measuredRuns, outputFile);
        }

        public BenchmarkRequest withBalls(List<Integer> value) {
            return new BenchmarkRequest(implementations, value, steps, seed, workers, warmupRuns, measuredRuns, outputFile);
        }

        public BenchmarkRequest withSteps(int value) {
            return new BenchmarkRequest(implementations, balls, value, seed, workers, warmupRuns, measuredRuns, outputFile);
        }

        public BenchmarkRequest withSeed(long value) {
            return new BenchmarkRequest(implementations, balls, steps, value, workers, warmupRuns, measuredRuns, outputFile);
        }

        public BenchmarkRequest withWorkers(int value) {
            return new BenchmarkRequest(implementations, balls, steps, seed, value, warmupRuns, measuredRuns, outputFile);
        }

        public BenchmarkRequest withWarmupRuns(int value) {
            return new BenchmarkRequest(implementations, balls, steps, seed, workers, value, measuredRuns, outputFile);
        }

        public BenchmarkRequest withMeasuredRuns(int value) {
            return new BenchmarkRequest(implementations, balls, steps, seed, workers, warmupRuns, value, outputFile);
        }

        public BenchmarkRequest withOutputFile(Path value) {
            return new BenchmarkRequest(implementations, balls, steps, seed, workers, warmupRuns, measuredRuns, value);
        }
    }

    /**
     * Report produced by the headless benchmark runner.
     *
     * @param outputFile CSV output path
     * @param rows measured benchmark rows
     */
    public record BenchmarkReport(
            Path outputFile,
            Path aggregatedOutputFile,
            Path speedupOutputFile,
            List<BenchmarkRunResult> rawResults,
            List<BenchmarkRow> rows) {
    }

    /**
     * Single measured benchmark row.
     *
     * @param implementation implementation name
     * @param balls ball count
     * @param workers worker count used by the implementation
     * @param steps simulation steps
     * @param seed random seed
     * @param runIndex 1-based measured run index
     * @param warmup whether the row represents a warmup run
     * @param elapsedMs measured elapsed time in milliseconds
     * @param throughput steps per second
     * @param coordinationMs estimated coordination time in milliseconds
     * @param coordinationRatio coordination time divided by elapsed time
     * @param tasksSubmitted tasks submitted during the run
     * @param stateHash final board-state hash
     * @param jvm JVM identification string
     * @param os operating system identification string
     * @param availableProcessors JVM-reported CPU count
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
            long stateHash,
            String jvm,
            String os,
            int availableProcessors) {
    }
}
