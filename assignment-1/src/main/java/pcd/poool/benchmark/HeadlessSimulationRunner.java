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

    private static final int DEFAULT_BALL_COUNT = 100;
    private static final int DEFAULT_THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final int DEFAULT_STEPS = 600;
    private static final long DEFAULT_SEED = 0L;
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
     * Missing arguments fall back to the defaults used by the benchmark
     * helpers.
     *
     * @param args optional CLI arguments
     */
    public static void main(String[] args) {
        var implementation = args.length > 0
                ? ImplementationType.fromString(args[0])
                : ImplementationType.SEQUENTIAL;
        int balls = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_BALL_COUNT;
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_THREAD_COUNT;
        int steps = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_STEPS;
        long seed = args.length > 4 ? Long.parseLong(args[4]) : DEFAULT_SEED;

        var result = run(implementation, balls, threads, steps, seed);
        System.out.printf(Locale.US,
                "implementation=%s balls=%d requested_threads=%d effective_threads=%d steps=%d seed=%d elapsed_ms=%.3f completed_steps=%d state_hash=%d%n",
                result.implementation().name().toLowerCase(Locale.ROOT),
                result.ballCount(),
                result.requestedThreadCount(),
                result.effectiveThreadCount(),
                result.simulationSteps(),
                result.seed(),
                result.elapsedMillis(),
                result.completedSteps(),
                result.stateHash());
    }

    /**
     * Runs one headless benchmark scenario.
     *
     * @param implementation execution strategy to use
     * @param ballCount number of small balls to generate
     * @param threadCount requested worker count for concurrent implementations
     * @param simulationSteps number of simulation steps to execute
     * @param seed random seed used to generate the initial board
     * @return benchmark result including elapsed time and final state hash
     */
    public static SimulationResult run(
            ImplementationType implementation,
            int ballCount,
            int threadCount,
            int simulationSteps,
            long seed) {
        if (implementation == null) {
            throw new IllegalArgumentException("implementation must not be null");
        }
        if (ballCount < 0) {
            throw new IllegalArgumentException("ballCount must be >= 0");
        }
        if (threadCount < 1) {
            throw new IllegalArgumentException("threadCount must be >= 1");
        }
        if (simulationSteps < 0) {
            throw new IllegalArgumentException("simulationSteps must be >= 0");
        }

        int effectiveThreadCount = implementation == ImplementationType.SEQUENTIAL ? 1 : threadCount;
        var boardConf = new SeededBoardConf(ballCount, seed);
        var result = runInternal(implementation, boardConf, ballCount, threadCount, effectiveThreadCount, simulationSteps, seed);
        blackhole = result.stateHash();
        return result;
    }

    private static SimulationResult runInternal(
            ImplementationType implementation,
            BoardConf boardConf,
            int ballCount,
            int requestedThreadCount,
            int effectiveThreadCount,
            int simulationSteps,
            long seed) {
        PhysicsStepper stepper;
        AutoCloseable closeable = null;
        switch (implementation) {
            case SEQUENTIAL -> stepper = new PhysicsEngine();
            case THREADS -> {
                var engine = new ThreadedPhysicsEngine(effectiveThreadCount);
                stepper = engine;
                closeable = engine;
            }
            case EXECUTOR -> {
                var engine = new TaskBasedPhysicsEngine(effectiveThreadCount);
                stepper = engine;
                closeable = engine;
            }
            default -> throw new IllegalStateException("unsupported implementation: " + implementation);
        }

        try {
            var board = new Board(stepper);
            board.init(boardConf);
            long start = System.nanoTime();
            for (int i = 0; i < simulationSteps; i++) {
                board.updateState(STEP_MILLIS);
            }
            long elapsed = System.nanoTime() - start;
            return new SimulationResult(
                    implementation,
                    ballCount,
                    requestedThreadCount,
                    effectiveThreadCount,
                    simulationSteps,
                    seed,
                    elapsed,
                    simulationSteps,
                    checksum(board));
        } finally {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (Exception ex) {
                    throw new IllegalStateException("failed to close benchmark engine", ex);
                }
            }
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
     * Supported execution strategies for the headless benchmark.
     */
    public enum ImplementationType {
        SEQUENTIAL,
        THREADS,
        EXECUTOR;

        /**
         * Parses a command-line token into an implementation type.
         *
         * @param value command-line token
         * @return parsed execution strategy
         */
        public static ImplementationType fromString(String value) {
            if (value == null) {
                throw new IllegalArgumentException("implementation type must not be null");
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "sequential", "seq" -> SEQUENTIAL;
                case "threads", "threaded", "thread" -> THREADS;
                case "executor", "task", "taskbased" -> EXECUTOR;
                default -> throw new IllegalArgumentException("unknown implementation type: " + value);
            };
        }
    }

    /**
     * Immutable benchmark result.
     *
     * @param implementation execution strategy used
     * @param ballCount number of small balls in the scenario
     * @param requestedThreadCount thread count requested by the caller
     * @param effectiveThreadCount thread count actually used by the engine
     * @param simulationSteps number of simulation steps requested
     * @param seed random seed used to generate the scenario
     * @param elapsedNanos elapsed time for the measured simulation loop
     * @param completedSteps number of steps completed successfully
     * @param stateHash final board-state hash consumed by the benchmark
     */
    public record SimulationResult(
            ImplementationType implementation,
            int ballCount,
            int requestedThreadCount,
            int effectiveThreadCount,
            int simulationSteps,
            long seed,
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
