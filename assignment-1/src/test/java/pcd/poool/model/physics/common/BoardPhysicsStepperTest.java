package pcd.poool.model.physics.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

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
    void fillCollisionBallsReusesTheProvidedBuffer() {
        var board = new Board((target, elapsedMillis) -> {});
        board.init(new EmptyBoardConf());

        var target = new ArrayList<Ball>();
        target.add(new Ball(new P2d(99, 99), 1.0, 1.0, new V2d(0, 0)));

        board.fillCollisionBalls(target);

        assertEquals(2, target.size());
        assertEquals(board.getPlayerBallEntity(), target.get(0));
        assertEquals(board.getBotBallEntity(), target.get(1));
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
}
