package pcd.poool.model.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

class PhysicsEngineTest {

    private static final double EPSILON = 1e-6;

    @Test
    void elasticCollisionExchangesVelocitiesForEqualMassBalls() {
        var left = new Ball(new P2d(-0.04, 0), 0.05, 1, new V2d(1, 0));
        var right = new Ball(new P2d(0.04, 0), 0.05, 1, new V2d(-1, 0));

        Ball.resolveCollision(left, right);

        assertEquals(-1.0, left.getVel().x(), EPSILON);
        assertEquals(1.0, right.getVel().x(), EPSILON);
    }

    @Test
    void frictionDecaysVelocityDuringMovement() {
        var ball = new Ball(new P2d(0, 0), 0.05, 1, new V2d(1, 0));

        ball.updateState(100, new Boundary(-2, -2, 2, 2));

        assertTrue(ball.getVel().abs() < 1.0);
        assertTrue(ball.getVel().abs() > 0.0);
    }

    @Test
    void wallBounceKeepsBallInsideBoundsAndFlipsVelocity() {
        var ball = new Ball(new P2d(0.98, 0), 0.05, 1, new V2d(1, 0));

        ball.updateState(100, new Boundary(-1, -1, 1, 1));

        assertEquals(0.95, ball.getPos().x(), EPSILON);
        assertTrue(ball.getVel().x() < 0);
    }

    @Test
    void holesRemoveSmallBallsAndMarkPlayerAsPocketed() {
        var board = new Board();
        board.init(new TestBoardConf(
                new Ball(new P2d(0.90, 0.90), 0.05, 1, new V2d(0, 0)),
                List.of(new Ball(new P2d(-0.90, 0.90), 0.03, 1, new V2d(0, 0)))));

        board.updateState(16);

        assertTrue(board.isPlayerBallPocketed());
        assertEquals(1, board.getPocketedSmallBalls());
        assertTrue(board.getBalls().isEmpty());
    }

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
