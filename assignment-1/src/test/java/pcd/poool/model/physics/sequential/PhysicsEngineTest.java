package pcd.poool.model.physics.sequential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.Boundary;
import pcd.poool.model.physics.common.Hole;

class PhysicsEngineTest {

    private static final double EPSILON = 1e-6;

    /**
     * Verifies that two balls of equal mass correctly exchange velocities along the collision axis
     * when they collide elastically.
     */
    @Test
    void elasticCollisionExchangesVelocitiesForEqualMassBalls() {
        var left = new Ball(new P2d(-0.04, 0), 0.05, 1, new V2d(1, 0));
        var right = new Ball(new P2d(0.04, 0), 0.05, 1, new V2d(-1, 0));

        Ball.resolveCollision(left, right);

        assertEquals(-1.0, left.getVel().x(), EPSILON);
        assertEquals(1.0, right.getVel().x(), EPSILON);
    }

    /**
     * Verifies that friction decays a ball's velocity over time, but keeps it moving
     * above zero until it falls below the minimum speed threshold.
     */
    @Test
    void frictionDecaysVelocityDuringMovement() {
        var ball = new Ball(new P2d(0, 0), 0.05, 1, new V2d(1, 0));

        ball.updateState(100, new Boundary(-2, -2, 2, 2));

        assertTrue(ball.getVel().abs() < 1.0);
        assertTrue(ball.getVel().abs() > 0.0);
    }

    /**
     * Verifies that a ball hitting a board boundary (wall bounce) gets its position adjusted
     * to remain inside bounds, and has its velocity component flipped elastically.
     */
    @Test
    void wallBounceKeepsBallInsideBoundsAndFlipsVelocity() {
        var ball = new Ball(new P2d(0.98, 0), 0.05, 1, new V2d(1, 0));

        ball.updateState(100, new Boundary(-1, -1, 1, 1));

        assertEquals(0.95, ball.getPos().x(), EPSILON);
        assertTrue(ball.getVel().x() < 0);
    }

    /**
     * Verifies that holes on the board correctly pocket small balls, remove them from active simulation,
     * and flag when the player cue ball itself gets pocketed.
     */
    @Test
    void holesRemoveSmallBallsAndMarkPlayerAsPocketed() {
        var board = new Board();
        board.init(new TestBoardConf(
                new Ball(new P2d(0.90, 0.90), 0.05, 1, new V2d(0, 0)),
                List.of(new Ball(new P2d(-0.90, 0.90), 0.03, 1, new V2d(0, 0)))));

        board.updateState(16);

        assertTrue(board.isPlayerBallPocketed());
        assertNull(board.getPlayerBall());
        assertEquals(1, board.getPocketedSmallBalls());
        assertTrue(board.getBalls().isEmpty());
    }

    /**
     * Verifies that stepping two identical board setups results in identical, deterministic states
     * over multiple simulation frames.
     */
    @Test
    void simulationProducesDeterministicSnapshots() {
        var first = boardWithLineOfBalls();
        var second = boardWithLineOfBalls();

        for (int i = 0; i < 120; i++) {
            first.updateState(16);
            second.updateState(16);
        }

        assertEquals(first.getPlayerBall(), second.getPlayerBall());
        assertEquals(first.getBalls(), second.getBalls());
        assertEquals(first.getPocketedSmallBalls(), second.getPocketedSmallBalls());
    }

    /**
     * Verifies that the sequential physics stepper can successfully process large scenarios
     * with thousands of balls without errors.
     */
    @Test
    void largeScenarioCanBeStepped() {
        var balls = new ArrayList<Ball>();
        for (int row = 0; row < 25; row++) {
            for (int col = 0; col < 40; col++) {
                balls.add(new Ball(
                        new P2d(-1.2 + col * 0.06, -0.7 + row * 0.06),
                        0.01,
                        0.25,
                        new V2d(0, 0)));
            }
        }
        var board = new Board();
        board.init(new TestBoardConf(
                new Ball(new P2d(0, -0.9), 0.05, 1, new V2d(0, 1)),
                balls));

        board.updateState(16);

        assertFalse(board.getBalls().isEmpty());
    }

    private Board boardWithLineOfBalls() {
        var board = new Board();
        board.init(new TestBoardConf(
                new Ball(new P2d(-0.5, 0), 0.05, 1, new V2d(1, 0)),
                List.of(
                        new Ball(new P2d(0, 0), 0.05, 1, new V2d(0, 0)),
                        new Ball(new P2d(0.5, 0), 0.05, 1, new V2d(0, 0)))));
        return board;
    }

    private static class TestBoardConf implements BoardConf {

        private final Ball playerBall;
        private final List<Ball> smallBalls;

        TestBoardConf(Ball playerBall, List<Ball> smallBalls) {
            this.playerBall = playerBall;
            this.smallBalls = smallBalls;
        }

        @Override
        public Boundary getBoardBoundary() {
            return new Boundary(-1, -1, 1, 1);
        }

        @Override
        public Ball getPlayerBall() {
            return playerBall;
        }

        @Override
        public List<Ball> getSmallBalls() {
            return smallBalls;
        }

        @Override
        public List<Hole> getHoles() {
            return List.of(
                    new Hole(new P2d(-0.9, 0.9), 0.12),
                    new Hole(new P2d(0.9, 0.9), 0.12));
        }
    }
}
