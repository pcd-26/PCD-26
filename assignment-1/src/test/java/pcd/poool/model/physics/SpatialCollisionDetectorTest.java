package pcd.poool.model.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

class SpatialCollisionDetectorTest {

    @Test
    void detectsPairsForBallsWhoseBoundingCellsOverlap() {
        var detector = new SpatialCollisionDetector();
        var balls = List.of(
                new Ball(new P2d(0, 0), 0.05, 1, new V2d(0, 0)),
                new Ball(new P2d(0.08, 0), 0.05, 1, new V2d(0, 0)),
                new Ball(new P2d(1, 1), 0.05, 1, new V2d(0, 0)));

        var pairs = detector.detectCollisionPairs(balls);

        assertEquals(1, pairs.size());
        assertEquals(new SpatialCollisionDetector.Pair(0, 1), pairs.get(0));
    }

    @Test
    void returnsPairsInDeterministicOrder() {
        var detector = new SpatialCollisionDetector();
        var balls = List.of(
                new Ball(new P2d(0, 0), 0.05, 1, new V2d(0, 0)),
                new Ball(new P2d(0.08, 0), 0.05, 1, new V2d(0, 0)),
                new Ball(new P2d(0.04, 0.04), 0.05, 1, new V2d(0, 0)));

        var pairs = detector.detectCollisionPairs(balls);

        assertEquals(List.of(
                new SpatialCollisionDetector.Pair(0, 1),
                new SpatialCollisionDetector.Pair(0, 2),
                new SpatialCollisionDetector.Pair(1, 2)), pairs);
    }

    @Test
    void returnsNoPairsForEmptyOrSingleBallScenarios() {
        var detector = new SpatialCollisionDetector();

        assertTrue(detector.detectCollisionPairs(List.of()).isEmpty());
        assertTrue(detector.detectCollisionPairs(List.of(
                new Ball(new P2d(0, 0), 0.05, 1, new V2d(0, 0)))).isEmpty());
    }
}
