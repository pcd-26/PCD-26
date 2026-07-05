package pcd.poool.model.physics.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Broad-phase collision detector based on a deterministic uniform grid.
 */
public class SpatialCollisionDetector {

    /**
     * Detects and returns all candidate collision ball pairs using a spatial grid broad-phase check.
     *
     * @param balls the list of all balls to check for collisions
     * @return a sorted, deduplicated list of Pairs representing overlapping/colliding candidate ball indexes
     */
    public List<Pair> detectCollisionPairs(List<Ball> balls) {
        if (balls.size() < 2) {
            return List.of();
        }

        double cellSize = SpatialGridSupport.computeCellSize(balls);
        Map<SpatialGridSupport.GridCell, List<Integer>> grid = new HashMap<>();

        for (int i = 0; i < balls.size(); i++) {
            for (var cell : SpatialGridSupport.occupiedCells(balls.get(i), cellSize)) {
                grid.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(i);
            }
        }

        Set<Pair> pairs = new HashSet<>();
        for (var indexes : grid.values()) {
            collectPairs(indexes, pairs);
        }

        var orderedPairs = new ArrayList<>(pairs);
        orderedPairs.sort(Comparator
                .comparingInt(Pair::firstIndex)
                .thenComparingInt(Pair::secondIndex));
        return orderedPairs;
    }

    /**
     * Helper method to generate all unique candidate pairs from a single grid cell's list of ball indexes.
     *
     * @param indexes list of ball indexes located in the same grid cell
     * @param pairs set to accumulate unique candidate pairs
     */
    private void collectPairs(List<Integer> indexes, Set<Pair> pairs) {
        for (int i = 0; i < indexes.size() - 1; i++) {
            for (int j = i + 1; j < indexes.size(); j++) {
                pairs.add(new Pair(
                        Math.min(indexes.get(i), indexes.get(j)),
                        Math.max(indexes.get(i), indexes.get(j))));
            }
        }
    }

    public record Pair(int firstIndex, int secondIndex) {}

}
