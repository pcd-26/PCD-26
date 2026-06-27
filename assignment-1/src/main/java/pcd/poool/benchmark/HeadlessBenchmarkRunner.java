package pcd.poool.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.sequential.PhysicsEngine;
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;

/**
 * Reproducible headless benchmark runner for the three simulation
 * implementations.
 *
 * <p>The benchmark prepares a fresh seeded board for each run, measures only
 * the simulation loop with {@link System#nanoTime()}, and writes one CSV row
 * per measured run.
 */
public final class HeadlessBenchmarkRunner {

    private static final List<Integer> DEFAULT_BALLS = List.of(100, 500, 1_000, 2_500, 5_000, 10_000);
    private static final List<BenchmarkConfig.ImplementationType> DEFAULT_IMPLEMENTATIONS =
            List.of(BenchmarkConfig.ImplementationType.SEQUENTIAL,
                    BenchmarkConfig.ImplementationType.THREADS,
                    BenchmarkConfig.ImplementationType.EXECUTOR);
    private static final int DEFAULT_STEPS = 1_000;
    private static final long DEFAULT_SEED = 42L;
    private static final int DEFAULT_WORKERS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_WARMUP_RUNS = 2;
    private static final int DEFAULT_MEASURED_RUNS = 5;
    private static final Path DEFAULT_OUTPUT_FILE = Path.of("benchmark", "results", "raw-results.csv");
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
        var rows = new ArrayList<BenchmarkRow>();

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
                        request.outputFile().getParent() == null ? Path.of(".") : request.outputFile().getParent());

                runWarmups(config);
                for (int runIndex = 1; runIndex <= request.measuredRuns(); runIndex++) {
                    var result = measureRun(config, runIndex);
                    if (result.failed()) {
                        throw new IllegalStateException("benchmark run failed: " + result.failureMessage());
                    }
                    rows.add(toRow(result, telemetry, config, runIndex));
                }
            }
        }

        HeadlessBenchmarkCsvWriter.write(request.outputFile(), rows);
        return new BenchmarkReport(request.outputFile(), List.copyOf(rows));
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
                DEFAULT_WORKERS,
                DEFAULT_WARMUP_RUNS,
                DEFAULT_MEASURED_RUNS,
                DEFAULT_OUTPUT_FILE);
    }

    private static void runWarmups(BenchmarkConfig config) {
        for (int i = 0; i < config.warmupRuns(); i++) {
            var result = measureRun(config, i + 1);
            if (result.failed()) {
                throw new IllegalStateException("warmup run failed: " + result.failureMessage());
            }
        }
    }

    private static BenchmarkRunResult measureRun(BenchmarkConfig config, int runIndex) {
        try (SimulationSession session = openSession(config)) {
            var result = BenchmarkRunner.time(runIndex, false, config.steps(), session::run);
            blackhole = result.checksum();
            return result;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to measure benchmark run", ex);
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
        var board = new Board(new PhysicsEngine());
        board.init(new SeededBenchmarkBoardConf(config.balls(), config.seed()));
        return new SimulationSession(board, null, config.steps());
    }

    private static SimulationSession openThreadedSession(BenchmarkConfig config) {
        var engine = new ThreadedPhysicsEngine(config.effectiveThreads());
        var board = new Board(engine);
        board.init(new SeededBenchmarkBoardConf(config.balls(), config.seed()));
        return new SimulationSession(board, engine, config.steps());
    }

    private static SimulationSession openTaskBasedSession(BenchmarkConfig config) {
        var engine = new TaskBasedPhysicsEngine(config.effectiveThreads());
        var board = new Board(engine);
        board.init(new SeededBenchmarkBoardConf(config.balls(), config.seed()));
        return new SimulationSession(board, engine, config.steps());
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
                result.checksum(),
                jvm,
                os,
                telemetry.availableProcessors());
    }

    private static long checksum(Board board) {
        synchronized (board) {
            long hash = 0x9E3779B97F4A7C15L;
            hash = mix(hash, board.getPocketedSmallBalls());
            hash = mix(hash, board.isPlayerBallPocketed() ? 1L : 0L);
            hash = mix(hash, board.isBotBallPocketed() ? 1L : 0L);

            var playerBall = board.getPlayerBallEntity();
            if (playerBall != null) {
                hash = hashBall(hash, playerBall);
            }

            var botBall = board.getBotBallEntity();
            if (botBall != null) {
                hash = hashBall(hash, botBall);
            }

            for (var ball : board.getSmallBallEntities()) {
                hash = hashBall(hash, ball);
            }
            return avalanche(hash);
        }
    }

    private static long hashBall(long hash, pcd.poool.model.physics.common.Ball ball) {
        hash = mix(hash, Double.doubleToLongBits(ball.getPos().x()));
        hash = mix(hash, Double.doubleToLongBits(ball.getPos().y()));
        hash = mix(hash, Double.doubleToLongBits(ball.getVel().x()));
        hash = mix(hash, Double.doubleToLongBits(ball.getVel().y()));
        hash = mix(hash, Double.doubleToLongBits(ball.getRadius()));
        hash = mix(hash, Double.doubleToLongBits(ball.getMass()));
        return hash;
    }

    private static long mix(long hash, long value) {
        long z = hash ^ value;
        z ^= z >>> 33;
        z *= 0xff51afd7ed558ccdL;
        z ^= z >>> 33;
        z *= 0xc4ceb9fe1a85ec53L;
        z ^= z >>> 33;
        return z;
    }

    private static long avalanche(long value) {
        return mix(value, value << 1);
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
                  [--output benchmark/results/raw-results.csv]
                """);
    }

    private static final class SimulationSession implements AutoCloseable {

        private final Board board;
        private final AutoCloseable engine;
        private final int steps;

        private SimulationSession(Board board, AutoCloseable engine, int steps) {
            this.board = board;
            this.engine = engine;
            this.steps = steps;
        }

        private long run() {
            for (int i = 0; i < steps; i++) {
                board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }
            return checksum(board);
        }

        @Override
        public void close() throws Exception {
            if (engine != null) {
                engine.close();
            }
        }
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
    public record BenchmarkReport(Path outputFile, List<BenchmarkRow> rows) {
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
            long stateHash,
            String jvm,
            String os,
            int availableProcessors) {
    }
}
