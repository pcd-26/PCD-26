package pcd.poool.model.physics.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.Player;

class BoardPhysicsStepperTest {

    /**
     * Verifies that Board's updateState() method correctly delegates the step simulation
     * execution to the injected PhysicsStepper strategy implementation.
     */
    @Test
    void boardDelegatesUpdatesToInjectedPhysicsStepper() {
        var elapsed = new AtomicLong();
        var board = new Board((target, elapsedMillis) -> elapsed.addAndGet(elapsedMillis));
        board.init(new EmptyBoardConf());

        board.updateState(42);

        assertEquals(42, elapsed.get());
    }

    /**
     * Verifies that the Board constructor throws an IllegalArgumentException if a null
     * PhysicsStepper strategy is supplied.
     */
    @Test
    void boardRejectsNullPhysicsStepper() {
        assertThrows(IllegalArgumentException.class, () -> new Board(null));
    }

    @Test
    void getCandidateCollisionBallsReturnsACopy() {
        var board = new Board((target, elapsedMillis) -> {});
        board.init(new EmptyBoardConf());

        var copy = board.getActiveBalls();
        copy.add(new Ball(new P2d(99, 99), 1.0, 1.0, new V2d(0, 0)));

        assertEquals(3, copy.size());
        assertEquals(2, board.getActiveBalls().size());
        assertEquals(board.getPlayerBallEntity(), copy.get(0));
        assertEquals(board.getBotBallEntity(), copy.get(1));
    }

    @Test
    void directCueTouchAssignsThePocketedBallToTheCorrectPlayer() {
        var board = new Board((target, elapsedMillis) -> {});
        board.init(new SinglePocketedBallConf());

        var playerBall = board.getPlayerBallEntity();
        var smallBall = board.getSmallBallEntities().get(0);

        board.recordCollision(playerBall, smallBall);
        board.applyHoleInteractions();

        assertEquals(1, board.consumePendingScoredSmallBalls(Player.HUMAN));
        assertEquals(0, board.consumePendingScoredSmallBalls(Player.BOT));
    }

    private static class EmptyBoardConf implements BoardConf {

        @Override
        public Boundary getBoardBoundary() {
            return new Boundary(-1, -1, 1, 1);
        }

        @Override
        public Ball getPlayerBall() {
            return new Ball(new P2d(0, 0), 0.05, 1.0, new V2d(0, 0));
        }

        @Override
        public List<Ball> getSmallBalls() {
            return List.of();
        }
    }

    private static class SinglePocketedBallConf implements BoardConf {

        @Override
        public Boundary getBoardBoundary() {
            return new Boundary(-1, -1, 1, 1);
        }

        @Override
        public Ball getPlayerBall() {
            return new Ball(new P2d(-0.5, 0), 0.05, 1.0, new V2d(0, 0));
        }

        @Override
        public Ball getBotBall() {
            return new Ball(new P2d(0.5, 0), 0.05, 1.0, new V2d(0, 0));
        }

        @Override
        public List<Ball> getSmallBalls() {
            return List.of(new Ball(new P2d(0.7, 0), 0.05, 1.0, new V2d(0, 0)));
        }

        @Override
        public List<Hole> getHoles() {
            return List.of(new Hole(new P2d(0.7, 0), 0.12));
        }
    }
}
