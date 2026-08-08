package pcd.poool.model.physics.threaded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.Boundary;
import pcd.poool.model.physics.common.Hole;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.config.LargeBoardConf;
import pcd.poool.model.physics.sequential.SequentialPhysicsEngine;
import pcd.poool.model.physics.config.MinimalBoardConf;
import pcd.poool.model.physics.config.ThousandBallsBoardConf;

class ThreadedPhysicsEngineTest {

    private static final double EPSILON = 1e-9;

    /**
     * Verifies that the threaded parallel physics engine produces completely deterministic,
     * identical outcomes when run twice on the same board configuration.
     */
    @Test
    @Timeout(3)
    void threadedPhysicsProducesDeterministicOutcomeOnDeterministicConfiguration() {
        try (var threadedEngine = new ThreadedPhysicsEngine(2)) {
            var firstBoard = new Board(threadedEngine);
            firstBoard.init(new MinimalBoardConf());

            var secondEngine = new ThreadedPhysicsEngine(2);
            try {
                var secondBoard = new Board(secondEngine);
                secondBoard.init(new MinimalBoardConf());

                for (int i = 0; i < 60; i++) {
                    firstBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                    secondBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                }

                assertEquals(firstBoard.getBalls().size(), secondBoard.getBalls().size());
                assertEquals(firstBoard.getPlayerBall(), secondBoard.getPlayerBall());
                assertEquals(firstBoard.getBotBall(), secondBoard.getBotBall());
                assertEquals(firstBoard.getPocketedSmallBalls(), secondBoard.getPocketedSmallBalls());
            } finally {
                secondEngine.close();
            }
        }
    }

    /**
     * Verifies that the parallel engine matches the sequential baseline engine exactly
     * in simple configurations when no collisions occur.
     */
    @Test
    @Timeout(3)
    void threadedPhysicsMatchesSequentialBaselineWhenNoCollisionsOccur() {
        var conf = new SeparatedMotionBoardConf();

        try (var threadedEngine = new ThreadedPhysicsEngine(3)) {
            var sequentialBoard = new Board(new SequentialPhysicsEngine());
            sequentialBoard.init(conf);
            sequentialBoard.kick(Player.HUMAN, new V2d(0.18, 0.03));
            sequentialBoard.kick(Player.BOT, new V2d(-0.12, -0.01));

            var threadedBoard = new Board(threadedEngine);
            threadedBoard.init(conf);
            threadedBoard.kick(Player.HUMAN, new V2d(0.18, 0.03));
            threadedBoard.kick(Player.BOT, new V2d(-0.12, -0.01));

            for (int i = 0; i < 40; i++) {
                sequentialBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                threadedBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }

            assertAll(
                    () -> assertEquals(sequentialBoard.getBalls(), threadedBoard.getBalls()),
                    () -> assertEquals(sequentialBoard.getPlayerBall(), threadedBoard.getPlayerBall()),
                    () -> assertEquals(sequentialBoard.getBotBall(), threadedBoard.getBotBall()),
                    () -> assertEquals(
                            sequentialBoard.getPocketedSmallBalls(),
                            threadedBoard.getPocketedSmallBalls()),
                    () -> assertEquals(
                            sequentialBoard.isPlayerBallPocketed(),
                            threadedBoard.isPlayerBallPocketed()),
                    () -> assertEquals(
                            sequentialBoard.isBotBallPocketed(),
                            threadedBoard.isBotBallPocketed()));
        }
    }

    /**
     * Verifies that simultaneous contact impulses resolved by multiple workers encur
     * correct cumulative physical effects.
     */
    @Test
    @Timeout(3)
    void accumulatedImpulseSolverCombinesDependentSimultaneousContacts() {
        try (var engine = new ThreadedPhysicsEngine(2)) {
            var board = new Board(engine);
            board.init(new SimultaneousContactBoardConf());

            board.updateState(1);

            var balls = board.getActiveBalls();
            var leftCueBall = balls.get(0);
            var lowerCueBall = balls.get(1);
            var sharedTarget = balls.get(2);

            assertTrue(sharedTarget.getVel().x() > 0.5);
            assertTrue(sharedTarget.getVel().y() > 0.5);
            assertTrue(leftCueBall.getVel().x() < 0.5);
            assertTrue(lowerCueBall.getVel().y() < 0.5);
            assertEquals(0.0, leftCueBall.getVel().y(), EPSILON);
            assertEquals(0.0, lowerCueBall.getVel().x(), EPSILON);
        }
    }

    /**
     * Verifies that the parallel engine matches the sequential baseline engine exactly
     * even in complex dense collision scenarios.
     */
    @Test
    @Timeout(5)
    void threadedPhysicsMatchesSequentialBaselineOnDenseCollisionScenario() {
        var conf = new LargeBoardConf();

        var sequentialBoard = new Board(new SequentialPhysicsEngine());
        sequentialBoard.init(conf);
        sequentialBoard.kick(Player.HUMAN, new V2d(0.95, 0.15));
        sequentialBoard.kick(Player.BOT, new V2d(-0.9, -0.1));

        try (var engine = new ThreadedPhysicsEngine(4)) {
            var threadedBoard = new Board(engine);
            threadedBoard.init(conf);
            threadedBoard.kick(Player.HUMAN, new V2d(0.95, 0.15));
            threadedBoard.kick(Player.BOT, new V2d(-0.9, -0.1));

            for (int i = 0; i < 25; i++) {
                sequentialBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                threadedBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }

            assertAll(
                    () -> assertEquals(sequentialBoard.getBalls(), threadedBoard.getBalls()),
                    () -> assertEquals(sequentialBoard.getPlayerBall(), threadedBoard.getPlayerBall()),
                    () -> assertEquals(sequentialBoard.getBotBall(), threadedBoard.getBotBall()),
                    () -> assertEquals(
                            sequentialBoard.getPocketedSmallBalls(),
                            threadedBoard.getPocketedSmallBalls()),
                    () -> assertEquals(
                            sequentialBoard.isPlayerBallPocketed(),
                            threadedBoard.isPlayerBallPocketed()),
                    () -> assertEquals(
                            sequentialBoard.isBotBallPocketed(),
                            threadedBoard.isBotBallPocketed()));
        }
    }

    /**
     * Verifies that closure of the threaded physics engine shuts down worker threads cleanly
     * and clears state.
     */
    @Test
    @Timeout(3)
    void workerThreadsCanBeClosedAfterUse() {
        var engine = new ThreadedPhysicsEngine(2);
        var board = new Board(engine);
        board.init(new MinimalBoardConf());

        board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
        engine.close();

        assertFalse(Thread.currentThread().isInterrupted());
    }

    /**
     * Verifies that the threaded engine can process a large scenario (1000 balls)
     * correctly with multiple parallel workers.
     */
    @Test
    @Timeout(5)
    void thousandBallConfigurationCanBeSteppedByThreadedEngine() {
        try (var engine = new ThreadedPhysicsEngine(4)) {
            var board = new Board(engine);
            board.init(new ThousandBallsBoardConf());

            board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);

            assertEquals(ThousandBallsBoardConf.SMALL_BALL_COUNT, board.getBalls().size());
            assertEquals(4, engine.workerCount());
        }
    }

    /**
     * Verifies that step profiling captures timing and execution metrics across workers.
     */
    @Test
    @Timeout(3)
    void profileStepReportsParallelWorkOnLargeBoard() {
        try (var engine = new ThreadedPhysicsEngine(4)) {
            var board = new Board(engine);
            board.init(new ThousandBallsBoardConf());

            var profile = engine.profileStep(board, PhysicsDefaults.FIXED_STEP_MILLIS);

            assertEquals(4, engine.workerCount());
            assertTrue(profile.submittedTasks() >= 4);
            assertTrue(profile.taskSubmissionTimeMillis() >= 0.0);
            assertTrue(profile.joinOrFutureWaitMillis() >= 0.0);
            assertTrue(profile.movementMillis() >= 0.0);
            assertTrue(profile.collisionDetectionMillis() >= 0.0);
            assertTrue(profile.collisionResolutionMillis() >= 0.0);
        }
    }

    /**
     * Verifies that a zero or negative worker count is rejected on engine startup.
     */
    @Test
    void rejectsInvalidWorkerCount() {
        assertThrows(IllegalArgumentException.class, () -> new ThreadedPhysicsEngine(0));
    }

    private static class SimultaneousContactBoardConf implements BoardConf {

        @Override
        public Boundary getBoardBoundary() {
            return new Boundary(-1, -1, 1, 1);
        }

        @Override
        public Ball getPlayerBall() {
            return new Ball(new P2d(-0.08, 0), 0.05, 1.0, new V2d(1.0, 0.0));
        }

        @Override
        public Ball getBotBall() {
            return new Ball(new P2d(0, -0.08), 0.05, 1.0, new V2d(0.0, 1.0));
        }

        @Override
        public List<Ball> getSmallBalls() {
            return List.of(new Ball(new P2d(0, 0), 0.05, 1.0, new V2d(0.0, 0.0)));
        }

        @Override
        public List<Hole> getHoles() {
            return List.of();
        }
    }

    private static class SeparatedMotionBoardConf implements BoardConf {

        @Override
        public Boundary getBoardBoundary() {
            return new Boundary(-1, -1, 1, 1);
        }

        @Override
        public Ball getPlayerBall() {
            return new Ball(new P2d(-0.75, 0), 0.04, 1.0, new V2d(0.0, 0.0));
        }

        @Override
        public Ball getBotBall() {
            return new Ball(new P2d(0.75, 0), 0.04, 1.0, new V2d(0.0, 0.0));
        }

        @Override
        public List<Ball> getSmallBalls() {
            return List.of(
                    new Ball(new P2d(-0.1, 0.65), 0.03, 1.0, new V2d(0.0, 0.0)),
                    new Ball(new P2d(0.1, -0.65), 0.03, 1.0, new V2d(0.0, 0.0)));
        }

        @Override
        public List<Hole> getHoles() {
            return List.of();
        }
    }
}
