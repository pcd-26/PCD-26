package pcd.poool.model.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.Player;

class TaskBasedPhysicsEngineTest {

    @Test
    void taskBasedPhysicsCanBeConfiguredWithExplicitPoolSize() {
        var engine = new TaskBasedPhysicsEngine(3);

        assertEquals(3, engine.poolSize());

        engine.close();
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

            assertEquals(sequentialBoard.getBalls(), taskBoard.getBalls());
            assertEquals(sequentialBoard.getPlayerBall(), taskBoard.getPlayerBall());
            assertEquals(sequentialBoard.getBotBall(), taskBoard.getBotBall());
            assertEquals(sequentialBoard.getPocketedSmallBalls(), taskBoard.getPocketedSmallBalls());
            assertTrue(taskBoard.getBalls().size() > 0);
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
}
