package pcd.poool.model.physics.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

class SpatialCollisionDetectorTest {

    /**
     * Verifies that the spatial grid correctly registers broad-phase collision candidate pairs
     * for balls residing in adjacent or overlapping grid cells, while ignoring distant balls.
     */
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

    /**
     * Verifies that candidate pair results are sorted consistently (first index smaller than second)
     * to guarantee deterministic order of physics execution.
     */
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

    /**
     * Verifies that list inputs with zero or a single ball return no pairwise candidates.
     */
    @Test
    void returnsNoPairsForEmptyOrSingleBallScenarios() {
        var detector = new SpatialCollisionDetector();

        assertTrue(detector.detectCollisionPairs(List.of()).isEmpty());
        assertTrue(detector.detectCollisionPairs(List.of(
                new Ball(new P2d(0, 0), 0.05, 1, new V2d(0, 0)))).isEmpty());
    }
}
