package pcd.poool.model.physics.taskbased;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import pcd.poool.model.physics.common.SpatialCollisionDetector.Pair;
import pcd.poool.model.physics.sequential.PhysicsEngine;
import pcd.poool.model.physics.config.ThousandBallsBoardConf;

class TaskBasedPhysicsEngineTest {

    private static final double EPSILON = 1e-9;

    @Test
    void taskBasedPhysicsCanBeConfiguredWithExplicitPoolSize() {
        var engine = new TaskBasedPhysicsEngine(3);

        assertEquals(3, engine.poolSize());

        engine.close();
    }

    @Test
    @Timeout(3)
    void taskBasedPhysicsCanStepASimpleBoard() {
        try (var engine = new TaskBasedPhysicsEngine(2)) {
            var board = new Board(engine);
            board.init(new MinimalTaskBoardConf());

            assertDoesNotThrow(() -> board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS));
            assertEquals(1, board.getBalls().size());
            assertTrue(board.getPlayerBall() != null);
        }
    }

    @Test
    @Timeout(3)
    void taskBasedPhysicsMatchesSequentialOutcomeWithoutCollisions() {
        var conf = new SeparatedMotionBoardConf();

        var sequentialBoard = new Board(new PhysicsEngine());
        sequentialBoard.init(conf);
        sequentialBoard.kick(Player.HUMAN, new V2d(0.18, 0.03));
        sequentialBoard.kick(Player.BOT, new V2d(-0.12, -0.01));

        try (var taskEngine = new TaskBasedPhysicsEngine(2)) {
            var taskBoard = new Board(taskEngine);
            taskBoard.init(conf);
            taskBoard.kick(Player.HUMAN, new V2d(0.18, 0.03));
            taskBoard.kick(Player.BOT, new V2d(-0.12, -0.01));

            for (int i = 0; i < 40; i++) {
                sequentialBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                taskBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }

            assertBoardSnapshotsClose(sequentialBoard.getBalls(), taskBoard.getBalls());
            assertBallSnapshotClose(sequentialBoard.getPlayerBall(), taskBoard.getPlayerBall());
            assertBallSnapshotClose(sequentialBoard.getBotBall(), taskBoard.getBotBall());
            assertEquals(sequentialBoard.getPocketedSmallBalls(), taskBoard.getPocketedSmallBalls());
            assertTrue(taskBoard.getBalls().size() > 0);
        }
    }

    @Test
    @Timeout(3)
    void taskBasedPhysicsMatchesSequentialOutcomeWithSingleWorker() {
        var conf = new SeparatedMotionBoardConf();

        var sequentialBoard = new Board(new PhysicsEngine());
        sequentialBoard.init(conf);
        sequentialBoard.kick(Player.HUMAN, new V2d(0.18, 0.03));
        sequentialBoard.kick(Player.BOT, new V2d(-0.12, -0.01));

        try (var taskEngine = new TaskBasedPhysicsEngine(1)) {
            var taskBoard = new Board(taskEngine);
            taskBoard.init(conf);
            taskBoard.kick(Player.HUMAN, new V2d(0.18, 0.03));
            taskBoard.kick(Player.BOT, new V2d(-0.12, -0.01));

            for (int i = 0; i < 40; i++) {
                sequentialBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                taskBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }

            assertBoardSnapshotsClose(sequentialBoard.getBalls(), taskBoard.getBalls());
            assertBallSnapshotClose(sequentialBoard.getPlayerBall(), taskBoard.getPlayerBall());
            assertBallSnapshotClose(sequentialBoard.getBotBall(), taskBoard.getBotBall());
            assertEquals(sequentialBoard.getPocketedSmallBalls(), taskBoard.getPocketedSmallBalls());
        }
    }

    @Test
    void stepAfterCloseFailsDeterministically() {
        var engine = new TaskBasedPhysicsEngine(2);
        var board = new Board(engine);
        board.init(new MinimalTaskBoardConf());

        engine.close();

        assertThrows(IllegalStateException.class, () -> engine.step(board, PhysicsDefaults.FIXED_STEP_MILLIS));
    }

    @Test
    void rejectsInvalidPoolSize() {
        assertThrows(IllegalArgumentException.class, () -> new TaskBasedPhysicsEngine(0));
    }

    @Test
    @Timeout(3)
    void taskFailuresArePropagatedToTheCaller() {
        try (var engine = new TaskBasedPhysicsEngine(4)) {
            var board = new Board(engine);
            board.init(new FailingTaskBoardConf());

            var failure = assertThrows(IllegalStateException.class, () ->
                    board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS));

            assertTrue(failure.getMessage().contains("task-based physics step failed")
                    || failure.getMessage().contains("Injected task failure"));
        }
    }

    @Test
    @Timeout(5)
    void thousandBallConfigurationCanBeSteppedByTaskBasedEngine() {
        try (var engine = new TaskBasedPhysicsEngine(4)) {
            var board = new Board(engine);
            board.init(new ThousandBallsBoardConf());

            board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);

            assertEquals(ThousandBallsBoardConf.SMALL_BALL_COUNT, board.getBalls().size());
            assertEquals(4, engine.poolSize());
        }
    }

    @Test
    void broadPhaseDeduplicatesCandidatePairsAcrossMergedCells() {
        try (var engine = new TaskBasedPhysicsEngine(2)) {
            var balls = List.of(
                    new Ball(new P2d(0.05, 0.0), 0.05, 1.0, new V2d(0.0, 0.0)),
                    new Ball(new P2d(0.06, 0.0), 0.05, 1.0, new V2d(0.0, 0.0)));

            var pairs = engine.detectCollisionPairs(balls);

            assertEquals(1, pairs.size());
            assertEquals(List.of(new Pair(0, 1)), pairs);
        }
    }

    @Test
    void broadPhaseReturnsDeterministicallyOrderedCandidatePairs() {
        try (var engine = new TaskBasedPhysicsEngine(4)) {
            var balls = List.of(
                    new Ball(new P2d(0.05, 0.0), 0.05, 1.0, new V2d(0.0, 0.0)),
                    new Ball(new P2d(0.06, 0.0), 0.05, 1.0, new V2d(0.0, 0.0)),
                    new Ball(new P2d(0.07, 0.0), 0.05, 1.0, new V2d(0.0, 0.0)));

            var firstRun = engine.detectCollisionPairs(balls);
            var secondRun = engine.detectCollisionPairs(balls);

            assertEquals(List.of(
                    new Pair(0, 1),
                    new Pair(0, 2),
                    new Pair(1, 2)), firstRun);
            assertEquals(firstRun, secondRun);
        }
    }

    @Test
    void broadPhaseIncludesSharedBoundaryCandidates() {
        try (var engine = new TaskBasedPhysicsEngine(1)) {
            var balls = List.of(
                    new Ball(new P2d(0.0, 0.0), 0.05, 1.0, new V2d(0.0, 0.0)),
                    new Ball(new P2d(0.15, 0.0), 0.05, 1.0, new V2d(0.0, 0.0)));

            var pairs = engine.detectCollisionPairs(balls);

            assertEquals(List.of(new Pair(0, 1)), pairs);
        }
    }

    @Test
    void broadPhaseSkipsAdjacentCellsWhenBallsDoNotShareOccupiedCells() {
        try (var engine = new TaskBasedPhysicsEngine(2)) {
            var balls = List.of(
                    new Ball(new P2d(0.05, 0.0), 0.05, 1.0, new V2d(0.0, 0.0)),
                    new Ball(new P2d(0.25, 0.0), 0.05, 1.0, new V2d(0.0, 0.0)));

            var pairs = engine.detectCollisionPairs(balls);

            assertEquals(List.of(), pairs);
        }
    }

    @Test
    void collisionRoundsContainOnlyIndependentPairs() {
        try (var engine = new TaskBasedPhysicsEngine(4)) {
            var rounds = engine.buildCollisionRounds(List.of(
                    new Pair(0, 1),
                    new Pair(2, 3),
                    new Pair(4, 5),
                    new Pair(1, 6),
                    new Pair(3, 7)), 8);

            assertEquals(2, rounds.size());
            for (var round : rounds) {
                var touchedBalls = new java.util.HashSet<Integer>();
                for (var pair : round) {
                    assertTrue(touchedBalls.add(pair.firstIndex()));
                    assertTrue(touchedBalls.add(pair.secondIndex()));
                }
            }
        }
    }

    @Test
    void collisionRoundsScheduleIndependentPairsAsEarlyAsPossible() {
        try (var engine = new TaskBasedPhysicsEngine(4)) {
            var rounds = engine.buildCollisionRounds(List.of(
                    new Pair(0, 1),
                    new Pair(0, 2),
                    new Pair(2, 3),
                    new Pair(4, 5)), 6);

            assertEquals(List.of(new Pair(0, 1), new Pair(2, 3), new Pair(4, 5)), rounds.get(0));
            assertEquals(List.of(new Pair(0, 2)), rounds.get(1));
        }
    }

    @Test
    @Timeout(6)
    void denseCollisionConfigurationIsDeterministicAcrossRepeatedRuns() {
        var conf = new DenseCollisionBoardConf();

        try (var firstEngine = new TaskBasedPhysicsEngine(4);
                var secondEngine = new TaskBasedPhysicsEngine(4)) {
            var firstBoard = new Board(firstEngine);
            firstBoard.init(conf);
            var secondBoard = new Board(secondEngine);
            secondBoard.init(conf);

            for (int i = 0; i < 12; i++) {
                firstBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                secondBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }

            assertBoardSnapshotsClose(firstBoard.getBalls(), secondBoard.getBalls());
            assertBallSnapshotClose(firstBoard.getPlayerBall(), secondBoard.getPlayerBall());
            assertBallSnapshotClose(firstBoard.getBotBall(), secondBoard.getBotBall());
            assertEquals(firstBoard.getPocketedSmallBalls(), secondBoard.getPocketedSmallBalls());
        }
    }

    @Test
    @Timeout(8)
    void highBallCountConfigurationCanBeSteppedRepeatedly() {
        try (var engine = new TaskBasedPhysicsEngine(4)) {
            var board = new Board(engine);
            board.init(new ThousandBallsBoardConf());

            for (int i = 0; i < 10; i++) {
                board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }

            assertEquals(ThousandBallsBoardConf.SMALL_BALL_COUNT, board.getBalls().size());
        }
    }

    private static void assertBoardSnapshotsClose(
            List<Board.BallSnapshot> expected,
            List<Board.BallSnapshot> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertBallSnapshotClose(expected.get(i), actual.get(i));
        }
    }

    private static void assertBallSnapshotClose(Board.BallSnapshot expected, Board.BallSnapshot actual) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual);
            return;
        }
        assertEquals(expected.radius(), actual.radius(), EPSILON);
        assertEquals(expected.pos().x(), actual.pos().x(), EPSILON);
        assertEquals(expected.pos().y(), actual.pos().y(), EPSILON);
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

    private static class MinimalTaskBoardConf implements BoardConf {

        @Override
        public Boundary getBoardBoundary() {
            return new Boundary(-1, -1, 1, 1);
        }

        @Override
        public Ball getPlayerBall() {
            return new Ball(new P2d(0, 0), 0.05, 1.0, new V2d(0.0, 0.0));
        }

        @Override
        public Ball getBotBall() {
            return new Ball(new P2d(0.2, 0), 0.05, 1.0, new V2d(0.0, 0.0));
        }

        @Override
        public List<Ball> getSmallBalls() {
            return List.of(new Ball(new P2d(-0.2, 0), 0.03, 1.0, new V2d(0.0, 0.0)));
        }

        @Override
        public List<Hole> getHoles() {
            return List.of();
        }
    }

    private static class FailingTaskBoardConf implements BoardConf {

        @Override
        public Boundary getBoardBoundary() {
            return new Boundary(-1, -1, 1, 1);
        }

        @Override
        public Ball getPlayerBall() {
            return new FailingBall(new P2d(0, 0), 0.05, 1.0, new V2d(0.2, 0.0));
        }

        @Override
        public Ball getBotBall() {
            return null;
        }

        @Override
        public List<Ball> getSmallBalls() {
            return List.of();
        }

        @Override
        public List<Hole> getHoles() {
            return List.of();
        }
    }

    private static final class FailingBall extends Ball {

        FailingBall(P2d pos, double radius, double mass, V2d vel) {
            super(pos, radius, mass, vel);
        }

        @Override
        public void updateState(long dt, Boundary bounds) {
            throw new IllegalStateException("Injected task failure");
        }
    }

    private static class DenseCollisionBoardConf implements BoardConf {

        @Override
        public Boundary getBoardBoundary() {
            return new Boundary(-1, -1, 1, 1);
        }

        @Override
        public Ball getPlayerBall() {
            return new Ball(new P2d(-0.35, 0), 0.05, 1.0, new V2d(0.8, 0.0));
        }

        @Override
        public Ball getBotBall() {
            return new Ball(new P2d(0.35, 0), 0.05, 1.0, new V2d(-0.8, 0.0));
        }

        @Override
        public List<Ball> getSmallBalls() {
            var balls = new java.util.ArrayList<Ball>();
            int columns = 8;
            int rows = 6;
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < columns; col++) {
                    double x = -0.25 + col * 0.07;
                    double y = -0.18 + row * 0.07;
                    balls.add(new Ball(new P2d(x, y), 0.03, 1.0, new V2d(0.0, 0.0)));
                }
            }
            return balls;
        }

        @Override
        public List<Hole> getHoles() {
            return List.of();
        }
    }
}
