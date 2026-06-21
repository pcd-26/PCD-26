package pcd.poool.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.Boundary;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.model.physics.sequential.PhysicsEngine;
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;

/**
 * Runs a deterministic simulation headlessly for benchmark comparisons.
 *
 * <p>The runner keeps GUI code out of the benchmark path. It builds a seeded
 * board configuration, runs a configurable number of physics steps with the
 * selected execution strategy, and returns a final state hash that consumes
 * the resulting board state so the JVM cannot optimize the simulation away.
 */
public final class HeadlessSimulationRunner {

    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;
    private static final long STEP_MILLIS = PhysicsDefaults.FIXED_STEP_MILLIS;
    private static final Boundary BOARD_BOUNDARY = new StandardGameBoardConf().getBoardBoundary();
    private static final double INNER_LEFT = -1.20;
    private static final double INNER_RIGHT = 1.20;
    private static final double INNER_BOTTOM = -0.55;
    private static final double INNER_TOP = 0.55;
    private static volatile long blackhole;

    private HeadlessSimulationRunner() {
    }

    /**
     * Runs the headless simulation from the command line.
     *
     * <p>Arguments, in order:
     * <ol>
     *   <li>implementation type: {@code sequential}, {@code threads}, or
     *       {@code executor}</li>
     *   <li>balls count</li>
     *   <li>thread count</li>
     *   <li>simulation steps</li>
     *   <li>random seed</li>
     * </ol>
     *
     * Missing arguments fall back to the defaults provided by
     * {@link BenchmarkConfig}.
     *
     * @param args optional CLI arguments
     */
    public static void main(String[] args) {
        var config = BenchmarkConfig.headlessSimulationDefaults();
        if (args.length > 0) {
            config = config.withImplementation(BenchmarkConfig.ImplementationType.parse(args[0]));
        }
        if (args.length > 1) {
            config = config.withBalls(Integer.parseInt(args[1]));
        }
        if (args.length > 2) {
            config = config.withThreads(Integer.parseInt(args[2]));
        }
        if (args.length > 3) {
            config = config.withSteps(Integer.parseInt(args[3]));
        }
        if (args.length > 4) {
            config = config.withSeed(Long.parseLong(args[4]));
        }

        var result = run(config);
        System.out.printf(Locale.US,
                "config=%s elapsed_ms=%.3f completed_steps=%d state_hash=%d%n",
                result.config().toKeyValueString(),
                result.elapsedMillis(),
                result.completedSteps(),
                result.stateHash());
    }

    /**
     * Runs one headless benchmark scenario.
     *
     * @param config benchmark configuration
     * @return benchmark result including elapsed time and final state hash
     */
    public static SimulationResult run(BenchmarkConfig config) {
        long start = System.nanoTime();
        var execution = simulateExecution(config);
        long elapsedNanos = System.nanoTime() - start;
        var result = new SimulationResult(config, elapsedNanos, config.steps(), execution.checksum());
        blackhole = result.stateHash();
        return result;
    }

    /**
     * Runs the headless simulation and returns only the final checksum.
     *
     * @param config benchmark configuration
     * @return checksum or state hash of the final board state
     */
    static long simulate(BenchmarkConfig config) {
        return simulateExecution(config).checksum();
    }

    /**
     * Runs the headless simulation and returns the final checksum plus optional
     * instrumentation.
     *
     * @param config benchmark configuration
     * @return execution result and optional synchronization metrics
     */
    static BenchmarkRunner.BenchmarkExecution simulateExecution(BenchmarkConfig config) {
        return switch (config.implementation()) {
            case SEQUENTIAL -> simulateSequential(config);
            case THREADS -> simulateThreaded(config);
            case EXECUTOR -> simulateTaskBased(config);
        };
    }

    private static BenchmarkRunner.BenchmarkExecution simulateSequential(BenchmarkConfig config) {
        var board = new Board(new PhysicsEngine());
        board.init(new SeededBoardConf(config.balls(), config.seed()));
        runSimulationLoop(board, config, null, null);
        return new BenchmarkRunner.BenchmarkExecution(checksum(board), BenchmarkInstrumentation.zero());
    }

    private static BenchmarkRunner.BenchmarkExecution simulateThreaded(BenchmarkConfig config) {
        var engine = new ThreadedPhysicsEngine(config.effectiveThreads());
        return simulateWithEngine(config, engine, null);
    }

    private static BenchmarkRunner.BenchmarkExecution simulateTaskBased(BenchmarkConfig config) {
        var engine = new TaskBasedPhysicsEngine(config.effectiveThreads());
        return simulateWithEngine(config, null, engine);
    }

    private static BenchmarkRunner.BenchmarkExecution simulateWithEngine(
            BenchmarkConfig config,
            ThreadedPhysicsEngine threadedEngine,
            TaskBasedPhysicsEngine taskBasedEngine) {
        AutoCloseable closeable = threadedEngine != null ? threadedEngine : taskBasedEngine;
        try {
            PhysicsStepper stepper = threadedEngine != null ? threadedEngine : taskBasedEngine;
            var board = new Board(stepper);
            board.init(new SeededBoardConf(config.balls(), config.seed()));
            var instrumentation = runSimulationLoop(board, config, threadedEngine, taskBasedEngine);
            return new BenchmarkRunner.BenchmarkExecution(checksum(board), instrumentation);
        } finally {
            closeQuietly(closeable);
        }
    }

    private static BenchmarkInstrumentation runSimulationLoop(
            Board board,
            BenchmarkConfig config,
            ThreadedPhysicsEngine threadedEngine,
            TaskBasedPhysicsEngine taskBasedEngine) {
        var instrumentation = BenchmarkInstrumentation.zero();
        for (int i = 0; i < config.steps(); i++) {
            if (config.instrumentationEnabled() && threadedEngine != null) {
                instrumentation = instrumentation.plus(toInstrumentation(threadedEngine.profileStep(board, STEP_MILLIS)));
            } else if (config.instrumentationEnabled() && taskBasedEngine != null) {
                instrumentation = instrumentation.plus(toInstrumentation(taskBasedEngine.profileStep(board, STEP_MILLIS)));
            } else {
                board.updateState(STEP_MILLIS);
            }
        }
        return instrumentation;
    }

    private static BenchmarkInstrumentation toInstrumentation(ThreadedPhysicsEngine.StepProfile profile) {
        if (profile == null) {
            return BenchmarkInstrumentation.zero();
        }
        return new BenchmarkInstrumentation(
                profile.syncTimeMillis(),
                profile.aggregationTimeMillis(),
                profile.taskSubmissionTimeMillis(),
                profile.joinOrFutureWaitMillis(),
                profile.lockAcquisitions(),
                profile.submittedTasks());
    }

    private static BenchmarkInstrumentation toInstrumentation(TaskBasedPhysicsEngine.StepProfile profile) {
        if (profile == null) {
            return BenchmarkInstrumentation.zero();
        }
        return new BenchmarkInstrumentation(
                profile.syncTimeMillis(),
                profile.aggregationTimeMillis(),
                profile.taskSubmissionTimeMillis(),
                profile.joinOrFutureWaitMillis(),
                profile.lockAcquisitions(),
                profile.submittedTasks());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to close benchmark engine", ex);
        }
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

    private static long hashBall(long hash, Ball ball) {
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

    /**
     * Immutable benchmark result.
     *
     * @param config benchmark configuration used for the run
     * @param elapsedNanos elapsed time for the measured simulation loop
     * @param completedSteps number of steps completed successfully
     * @param stateHash final board-state hash consumed by the benchmark
     */
    public record SimulationResult(
            BenchmarkConfig config,
            long elapsedNanos,
            int completedSteps,
            long stateHash) {

        /**
         * Gets the elapsed time in milliseconds.
         *
         * @return elapsed time in milliseconds
         */
        public double elapsedMillis() {
            return elapsedNanos / NANOS_PER_MILLISECOND;
        }
    }

    private static final class SeededBoardConf implements BoardConf {

        private static final double CUE_RADIUS = 0.05;
        private static final double MAX_SMALL_BALL_RADIUS = 0.05;
        private static final double MIN_SMALL_BALL_RADIUS = 0.004;
        private static final double JITTER_FRACTION = 0.18;
        private static final double VELOCITY_SCALE = 0.18;

        private final int ballCount;
        private final long seed;
        private final List<Ball> smallBalls;

        private SeededBoardConf(int ballCount, long seed) {
            this.ballCount = ballCount;
            this.seed = seed;
            this.smallBalls = buildSmallBalls();
        }

        @Override
        public Boundary getBoardBoundary() {
            return BOARD_BOUNDARY;
        }

        @Override
        public Ball getPlayerBall() {
            return Ball.ofUniformMaterial(new P2d(0.0, -0.72), CUE_RADIUS, new V2d(0, 0));
        }

        @Override
        public Ball getBotBall() {
            return Ball.ofUniformMaterial(new P2d(0.0, 0.62), CUE_RADIUS, new V2d(0, 0));
        }

        @Override
        public List<Ball> getSmallBalls() {
            return List.copyOf(smallBalls);
        }

        private List<Ball> buildSmallBalls() {
            if (ballCount == 0) {
                return List.of();
            }

            double usableWidth = INNER_RIGHT - INNER_LEFT;
            double usableHeight = INNER_TOP - INNER_BOTTOM;
            int columns = Math.max(1, (int) Math.ceil(Math.sqrt(ballCount * (usableWidth / usableHeight))));
            int rows = Math.max(1, (int) Math.ceil((double) ballCount / columns));
            double cellWidth = usableWidth / columns;
            double cellHeight = usableHeight / rows;
            double radius = Math.max(
                    MIN_SMALL_BALL_RADIUS,
                    Math.min(MAX_SMALL_BALL_RADIUS, Math.min(cellWidth, cellHeight) * 0.22));
            double jitterX = cellWidth * JITTER_FRACTION;
            double jitterY = cellHeight * JITTER_FRACTION;
            double velocityLimit = Math.min(VELOCITY_SCALE, Math.min(cellWidth, cellHeight));
            var rng = new SplittableRandom(seed);
            var balls = new ArrayList<Ball>(ballCount);

            for (int index = 0; index < ballCount; index++) {
                int row = index / columns;
                int column = index % columns;
                double baseX = INNER_LEFT + (column + 0.5) * cellWidth;
                double baseY = INNER_BOTTOM + (row + 0.5) * cellHeight;
                double x = clamp(baseX + centeredJitter(rng, jitterX), INNER_LEFT + radius, INNER_RIGHT - radius);
                double y = clamp(baseY + centeredJitter(rng, jitterY), INNER_BOTTOM + radius, INNER_TOP - radius);
                double vx = centeredJitter(rng, velocityLimit);
                double vy = centeredJitter(rng, velocityLimit);
                balls.add(Ball.ofUniformMaterial(new P2d(x, y), radius, new V2d(vx, vy)));
            }
            return balls;
        }

        private double centeredJitter(SplittableRandom rng, double amplitude) {
            return (rng.nextDouble() * 2.0 - 1.0) * amplitude;
        }

        private double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
