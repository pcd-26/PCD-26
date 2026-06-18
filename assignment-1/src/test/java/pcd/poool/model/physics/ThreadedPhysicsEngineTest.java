package pcd.poool.model.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.config.MinimalBoardConf;
import pcd.poool.model.physics.config.ThousandBallsBoardConf;

class ThreadedPhysicsEngineTest {

    private static final double EPSILON = 1e-9;

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

    @Test
    @Timeout(3)
    void accumulatedImpulseSolverCombinesDependentSimultaneousContacts() {
        try (var engine = new ThreadedPhysicsEngine(2)) {
            var board = new Board(engine);
            board.init(new SimultaneousContactBoardConf());

            board.updateState(1);

            var balls = board.getCollisionBalls();
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
}
