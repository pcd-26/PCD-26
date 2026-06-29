package pcd.poool.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.sequential.PhysicsEngine;
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;
import pcd.poool.view.board.View;
import pcd.poool.view.board.ViewModel;

/**
 * Dedicated GUI responsiveness benchmark that measures frame latency under
 * increasing simulation load.
 */
public final class GuiResponsivenessBenchmarkRunner {

    private static final List<BenchmarkConfig.ImplementationType> DEFAULT_IMPLEMENTATIONS =
            List.of(BenchmarkConfig.ImplementationType.SEQUENTIAL,
                    BenchmarkConfig.ImplementationType.THREADS,
                    BenchmarkConfig.ImplementationType.EXECUTOR);
    private static final List<Integer> DEFAULT_BALLS = List.of(100, 500, 1_000, 2_500, 5_000, 10_000);
    private static final int DEFAULT_STEPS = 240;
    private static final long DEFAULT_SEED = 42L;
    private static final int DEFAULT_WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_WARMUP_RUNS = 2;
    private static final int DEFAULT_MEASURED_RUNS = 5;
    private static final Path DEFAULT_OUTPUT_FILE = defaultAssignmentPath("benchmarks", "results", "raw-gui-results.csv");
    private static final int VIEW_WIDTH = 1_200;
    private static final int VIEW_HEIGHT = 800;
    private static volatile long blackhole;

    private GuiResponsivenessBenchmarkRunner() {
    }

    /**
     * Runs the GUI responsiveness benchmark from the command line.
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
                    "gui_benchmark_completed output=%s aggregated=%s rows=%d implementations=%d balls=%d%n",
                    report.outputFile(),
                    report.aggregatedOutputFile(),
                    report.rows().size(),
                    request.implementations().size(),
                    request.balls().size());
        } catch (Exception ex) {
            System.err.printf(Locale.US, "gui_benchmark_failed message=%s%n", ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Runs the GUI benchmark matrix and writes raw and aggregated CSV outputs.
     *
     * @param request benchmark request
     * @return execution report
     * @throws IOException if writing the CSV files fails
     */
    public static BenchmarkReport run(BenchmarkRequest request) throws IOException {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("GUI responsiveness benchmark requires a graphical environment");
        }

        var telemetry = RuntimeTelemetry.capture();
        var rows = new ArrayList<BenchmarkRow>();
        Path outputDir = request.outputFile().getParent() == null ? Path.of(".") : request.outputFile().getParent();
        RuntimeTelemetryCsvWriter.export(outputDir, telemetry);
        GuiResponsivenessBenchmarkCsvWriter.initialize(request.outputFile());

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
                        true,
                        false,
                        outputDir);

                runWarmups(config);
                for (int runIndex = 1; runIndex <= request.measuredRuns(); runIndex++) {
                    var result = measureRun(config, runIndex);
                    var row = toRow(result, telemetry, config, runIndex);
                    rows.add(row);
                    GuiResponsivenessBenchmarkCsvWriter.append(request.outputFile(), row);
                }
            }
        }

        var derived = GuiResponsivenessBenchmarkResultsPostProcessor.process(request.outputFile());
        return new BenchmarkReport(request.outputFile(), derived.aggregatedFile(), List.copyOf(rows));
    }

    /**
     * Creates the default GUI benchmark request.
     *
     * @return default request comparing all implementations
     */
    public static BenchmarkRequest defaults() {
        return new BenchmarkRequest(
                DEFAULT_IMPLEMENTATIONS,
                DEFAULT_BALLS,
                DEFAULT_STEPS,
                DEFAULT_SEED,
                DEFAULT_WORKERS,
                DEFAULT_WARMUP_RUNS,
                DEFAULT_MEASURED_RUNS,
                DEFAULT_OUTPUT_FILE);
    }

    private static void runWarmups(BenchmarkConfig config) {
        for (int i = 0; i < config.warmupRuns(); i++) {
            var result = measureRun(config, i + 1);
            blackhole ^= Double.doubleToLongBits(result.avgFrameMs());
        }
    }

    private static GuiRunResult measureRun(BenchmarkConfig config, int runIndex) {
        try (SimulationSession session = openSession(config)) {
            var result = session.run();
            blackhole ^= Double.doubleToLongBits(result.avgFrameMs()) ^ runIndex;
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to measure GUI benchmark run", ex);
        }
    }

    private static SimulationSession openSession(BenchmarkConfig config) {
        return switch (config.implementation()) {
            case SEQUENTIAL -> openSequentialSession(config);
            case THREADS -> openThreadedSession(config);
            case EXECUTOR -> openTaskBasedSession(config);
        };
    }

    private static SimulationSession openSequentialSession(BenchmarkConfig config) {
        var stepper = new PhysicsEngine();
        return createSession(config, stepper);
    }

    private static SimulationSession openThreadedSession(BenchmarkConfig config) {
        var stepper = new ThreadedPhysicsEngine(config.effectiveThreads());
        return createSession(config, stepper);
    }

    private static SimulationSession openTaskBasedSession(BenchmarkConfig config) {
        var stepper = new TaskBasedPhysicsEngine(config.effectiveThreads());
        return createSession(config, stepper);
    }

    private static SimulationSession createSession(BenchmarkConfig config, PhysicsStepper stepper) {
        var board = new Board(stepper);
        board.init(new SeededBenchmarkBoardConf(config.balls(), config.seed()));
        var viewModel = new ViewModel();
        var view = new View(viewModel, VIEW_WIDTH, VIEW_HEIGHT);
        return new SimulationSession(board, viewModel, view, stepper, config.steps());
    }

    private static BenchmarkRow toRow(
            GuiRunResult result,
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
                result.avgFrameMs(),
                result.p95FrameMs(),
                result.maxFrameMs(),
                result.avgFps(),
                result.framesAbove16Ms(),
                result.framesAbove33Ms(),
                jvm,
                os,
                telemetry.availableProcessors());
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

    private static void printUsage() {
        System.out.println("""
                Usage: java -cp assignment-1/target/classes pcd.poool.benchmark.GuiResponsivenessBenchmarkRunner \
                  [--implementation all|sequential|threads|executor] \
                  [--balls 100,500,1000,2500,5000,10000] \
                  [--steps N] \
                  [--seed N] \
                  [--workers N] \
                  [--warmup N] \
                  [--measured N] \
                  [--output benchmarks/results/raw-gui-results.csv]
                """);
    }

    private static Path defaultAssignmentPath(String... segments) {
        Path assignmentRoot = Path.of("assignment-1");
        if (Files.isDirectory(assignmentRoot)) {
            return assignmentRoot.resolve(Path.of("", segments));
        }
        return Path.of("", segments);
    }

    private static final class SimulationSession implements AutoCloseable {

        private final Board board;
        private final ViewModel viewModel;
        private final View view;
        private final PhysicsStepper stepper;
        private final int steps;

        private SimulationSession(Board board, ViewModel viewModel, View view, PhysicsStepper stepper, int steps) {
            this.board = board;
            this.viewModel = viewModel;
            this.view = view;
            this.stepper = stepper;
            this.steps = steps;
        }

        private GuiRunResult run() throws Exception {
            var frameTimes = new ArrayList<Double>(steps);
            long startNanos = System.nanoTime();
            for (int i = 0; i < steps; i++) {
                long frameStart = System.nanoTime();
                board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                viewModel.update(board, 0);
                view.render();
                frameTimes.add((System.nanoTime() - frameStart) / 1_000_000.0);
            }
            long elapsedNanos = System.nanoTime() - startNanos;
            return GuiRunResult.from(frameTimes, elapsedNanos);
        }

        @Override
        public void close() throws Exception {
            try {
                view.close();
            } finally {
                if (stepper instanceof AutoCloseable autoCloseable) {
                    autoCloseable.close();
                }
            }
        }
    }

    /**
     * Benchmark request parameters.
     *
     * @param implementations implementation types to compare
     * @param balls ball counts to benchmark
     * @param steps simulation steps per run
     * @param seed random seed
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
     * Report produced by the GUI benchmark runner.
     *
     * @param outputFile raw CSV output path
     * @param aggregatedOutputFile aggregated CSV output path
     * @param rows measured benchmark rows
     */
    public record BenchmarkReport(Path outputFile, Path aggregatedOutputFile, List<BenchmarkRow> rows) {
    }

    /**
     * Single measured GUI benchmark row.
     *
     * @param implementation implementation name
     * @param balls ball count
     * @param workers worker count used by the implementation
     * @param steps simulation steps
     * @param seed random seed
     * @param runIndex 1-based measured run index
     * @param avgFrameMs average frame time in milliseconds
     * @param p95FrameMs 95th percentile frame time in milliseconds
     * @param maxFrameMs maximum frame time in milliseconds
     * @param avgFps average frames per second
     * @param framesAbove16Ms frames slower than 16 ms
     * @param framesAbove33Ms frames slower than 33 ms
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
            double avgFrameMs,
            double p95FrameMs,
            double maxFrameMs,
            double avgFps,
            long framesAbove16Ms,
            long framesAbove33Ms,
            String jvm,
            String os,
            int availableProcessors) {
    }

    private record GuiRunResult(
            double avgFrameMs,
            double p95FrameMs,
            double maxFrameMs,
            double avgFps,
            long framesAbove16Ms,
            long framesAbove33Ms) {

        private static GuiRunResult from(List<Double> frameTimesMs, long elapsedNanos) {
            if (frameTimesMs.isEmpty()) {
                return new GuiRunResult(0.0, 0.0, 0.0, 0.0, 0L, 0L);
            }
            double sum = 0.0;
            double max = 0.0;
            long above16 = 0L;
            long above33 = 0L;
            for (double value : frameTimesMs) {
                sum += value;
                max = Math.max(max, value);
                if (value > 16.0) {
                    above16++;
                }
                if (value > 33.0) {
                    above33++;
                }
            }
            double avg = sum / frameTimesMs.size();
            double p95 = percentile(frameTimesMs, 0.95);
            double fps = elapsedNanos <= 0L ? 0.0 : frameTimesMs.size() * BenchmarkRunner.NANOS_PER_SECOND / elapsedNanos;
            return new GuiRunResult(avg, p95, max, fps, above16, above33);
        }

        private static double percentile(List<Double> values, double percentile) {
            var sorted = new ArrayList<>(values);
            sorted.sort(Double::compareTo);
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            index = Math.max(0, Math.min(index, sorted.size() - 1));
            return sorted.get(index);
        }
    }
}
