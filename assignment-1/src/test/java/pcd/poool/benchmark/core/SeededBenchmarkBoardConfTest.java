package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pcd.poool.model.physics.common.Ball;

class SeededBenchmarkBoardConfTest {

    @Test
    void averageCaseSpreadsBallsAcrossGrid() {
        var conf = new SeededBenchmarkBoardConf(100, 42L, false);
        var balls = conf.getSmallBalls();
        assertEquals(100, balls.size());

        // Verify that they are not all concentrated at the origin
        double sumX = 0;
        double sumY = 0;
        for (var ball : balls) {
            sumX += Math.abs(ball.getPos().x());
            sumY += Math.abs(ball.getPos().y());
        }
        assertTrue(sumX / balls.size() > 0.1);
        assertTrue(sumY / balls.size() > 0.1);
    }

    @Test
    void worstCaseConcentratesBallsInSingleCell() {
        var conf = new SeededBenchmarkBoardConf(100, 42L, true);
        var balls = conf.getSmallBalls();
        assertEquals(100, balls.size());

        // Get the ball radius
        double radius = balls.get(0).getRadius();
        double bound = radius * 0.9;

        // Verify all ball coordinates fall within the expected concentrated box
        for (var ball : balls) {
            assertTrue(Math.abs(ball.getPos().x()) <= bound + 1e-9, 
                    "x coordinate " + ball.getPos().x() + " exceeds bound " + bound);
            assertTrue(Math.abs(ball.getPos().y()) <= bound + 1e-9, 
                    "y coordinate " + ball.getPos().y() + " exceeds bound " + bound);
        }
    }

    @Test
    void defaultConstructorMatchesAverageCase() {
        var confDefault = new SeededBenchmarkBoardConf(50, 99L);
        var confAverage = new SeededBenchmarkBoardConf(50, 99L, false);

        var ballsDefault = confDefault.getSmallBalls();
        var ballsAverage = confAverage.getSmallBalls();

        assertEquals(ballsAverage.size(), ballsDefault.size());
        for (int i = 0; i < ballsAverage.size(); i++) {
            assertEquals(ballsAverage.get(i).getPos().x(), ballsDefault.get(i).getPos().x());
            assertEquals(ballsAverage.get(i).getPos().y(), ballsDefault.get(i).getPos().y());
        }
    }
}
