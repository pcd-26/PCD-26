package pcd.poool.model.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

class BoardPhysicsStepperTest {

    @Test
    void boardDelegatesUpdatesToInjectedPhysicsStepper() {
        var elapsed = new AtomicLong();
        var board = new Board((target, elapsedMillis) -> elapsed.addAndGet(elapsedMillis));
        board.init(new EmptyBoardConf());

        board.updateState(42);

        assertEquals(42, elapsed.get());
    }

    @Test
    void boardRejectsNullPhysicsStepper() {
        assertThrows(IllegalArgumentException.class, () -> new Board(null));
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
