package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pcd.poool.benchmark.core.SeededBenchmarkBoardConf;
import pcd.poool.model.physics.common.Ball;

class SeededBenchmarkBoardConfTest {

    @Test
    void averageCaseSpreadsBallsAcrossGrid() {
        var conf = new SeededBenchmarkBoardConf(100, 42L);
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
    void repeatedConstructionWithSameSeedIsDeterministic() {
        var confA = new SeededBenchmarkBoardConf(50, 99L);
        var confB = new SeededBenchmarkBoardConf(50, 99L);

        var ballsA = confA.getSmallBalls();
        var ballsB = confB.getSmallBalls();

        assertEquals(ballsA.size(), ballsB.size());
        for (int i = 0; i < ballsA.size(); i++) {
            assertEquals(ballsA.get(i).getRadius(), ballsB.get(i).getRadius());
            assertEquals(ballsA.get(i).getPos().x(), ballsB.get(i).getPos().x());
            assertEquals(ballsA.get(i).getPos().y(), ballsB.get(i).getPos().y());
            assertEquals(ballsA.get(i).getVel().x(), ballsB.get(i).getVel().x());
            assertEquals(ballsA.get(i).getVel().y(), ballsB.get(i).getVel().y());
        }
    }

    @Test
    void zeroBallsProducesEmptyList() {
        var conf = new SeededBenchmarkBoardConf(0, 99L);
        var balls = conf.getSmallBalls();

        assertTrue(balls.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> balls.add(null));
    }
}
