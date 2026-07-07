package pcd.poool.benchmark;

import java.util.Locale;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
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
            case SEQUENTIAL, SEQUENTIAL_WORST -> simulateSequential(config);
            case THREADS, THREADS_WORST -> simulateThreaded(config);
            case EXECUTOR, EXECUTOR_WORST -> simulateTaskBased(config);
        };
    }

    private static BenchmarkRunner.BenchmarkExecution simulateSequential(BenchmarkConfig config) {
        var board = new Board(new PhysicsEngine());
        board.init(new SeededBenchmarkBoardConf(config.balls(), config.seed(), config.worstCase()));
        runSimulationLoop(board, config, null, null);
        return new BenchmarkRunner.BenchmarkExecution(
                checksum(board),
                BenchmarkInstrumentation.zero(),
                captureFingerprint(board));
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
            board.init(new SeededBenchmarkBoardConf(config.balls(), config.seed(), config.worstCase()));
            var instrumentation = runSimulationLoop(board, config, threadedEngine, taskBasedEngine);
            return new BenchmarkRunner.BenchmarkExecution(
                    checksum(board),
                    instrumentation,
                    captureFingerprint(board));
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
                profile.submittedTasks(),
                profile.stateReadMillis(),
                profile.partitionMillis(),
                profile.movementMillis(),
                profile.holeInteractionMillis(),
                profile.collisionDetectionMillis(),
                profile.collisionResolutionMillis(),
                profile.mergeApplyMillis());
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
                profile.submittedTasks(),
                profile.stateReadMillis(),
                profile.partitionMillis(),
                profile.movementMillis(),
                profile.holeInteractionMillis(),
                profile.collisionDetectionMillis(),
                profile.collisionResolutionMillis(),
                profile.mergeApplyMillis());
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

    private static BenchmarkStateFingerprint captureFingerprint(Board board) {
        synchronized (board) {
            var bounds = board.getBounds();
            var player = board.getPlayerBall();
            var bot = board.getBotBall();
            var smallBalls = board.getBalls();
            long hash = 0xD6E8FEB86659FD93L;
            boolean hasNaN = false;
            boolean withinBounds = true;

            hash = mix(hash, board.getPocketedSmallBalls());
            hash = mix(hash, board.isPlayerBallPocketed() ? 1L : 0L);
            hash = mix(hash, board.isBotBallPocketed() ? 1L : 0L);
            hash = mix(hash, smallBalls.size());

            if (player != null) {
                hash = mix(hash, fingerprintBall(player));
                hasNaN |= hasNaN(player);
                withinBounds &= isWithinBounds(player, bounds);
            }

            if (bot != null) {
                hash = mix(hash, fingerprintBall(bot));
                hasNaN |= hasNaN(bot);
                withinBounds &= isWithinBounds(bot, bounds);
            }

            for (var ball : smallBalls) {
                hash = mix(hash, fingerprintBall(ball));
                hasNaN |= hasNaN(ball);
                withinBounds &= isWithinBounds(ball, bounds);
            }

            return new BenchmarkStateFingerprint(
                    checksum(board),
                    avalanche(hash),
                    smallBalls.size(),
                    board.getPocketedSmallBalls(),
                    board.isPlayerBallPocketed(),
                    board.isBotBallPocketed(),
                    hasNaN,
                    withinBounds);
        }
    }

    private static boolean hasNaN(Board.BallSnapshot ball) {
        return Double.isNaN(ball.pos().x())
                || Double.isNaN(ball.pos().y())
                || Double.isNaN(ball.radius());
    }

    private static boolean isWithinBounds(Board.BallSnapshot ball, pcd.poool.model.physics.common.Boundary bounds) {
        if (bounds == null) {
            return true;
        }
        return ball.pos().x() >= bounds.x0() - 1e-9
                && ball.pos().x() <= bounds.x1() + 1e-9
                && ball.pos().y() >= bounds.y0() - 1e-9
                && ball.pos().y() <= bounds.y1() + 1e-9;
    }

    private static long fingerprintBall(Board.BallSnapshot ball) {
        long hash = 0x9E3779B97F4A7C15L;
        hash = mix(hash, Double.doubleToLongBits(ball.pos().x()));
        hash = mix(hash, Double.doubleToLongBits(ball.pos().y()));
        hash = mix(hash, Double.doubleToLongBits(ball.radius()));
        return hash;
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
}
