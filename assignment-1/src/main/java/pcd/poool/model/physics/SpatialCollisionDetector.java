package pcd.poool.model.physics;

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
class SpatialCollisionDetector {

    List<Pair> detectCollisionPairs(List<Ball> balls) {
        if (balls.size() < 2) {
            return List.of();
        }

        double cellSize = computeCellSize(balls);
        Map<Cell, List<Integer>> grid = new HashMap<>();

        for (int i = 0; i < balls.size(); i++) {
            for (var cell : occupiedCells(balls.get(i), cellSize)) {
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

    private double computeCellSize(List<Ball> balls) {
        double minRadius = balls.stream().mapToDouble(Ball::getRadius)
                .min()
                .orElse(PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
        return Math.max(minRadius * PhysicsDefaults.RADIUS_TO_DIAMETER, PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
    }

    private void collectPairs(List<Integer> indexes, Set<Pair> pairs) {
        for (int i = 0; i < indexes.size() - 1; i++) {
            for (int j = i + 1; j < indexes.size(); j++) {
                pairs.add(new Pair(
                        Math.min(indexes.get(i), indexes.get(j)),
                        Math.max(indexes.get(i), indexes.get(j))));
            }
        }
    }

    private List<Cell> occupiedCells(Ball ball, double cellSize) {
        /*
         * A ball may be larger than the chosen cell size. Registering every
         * covered cell keeps candidate generation correct even when player and
         * small balls have different radii.
         */
        int x0 = toCellCoordinate(ball.getPos().x() - ball.getRadius(), cellSize);
        int x1 = toCellCoordinate(ball.getPos().x() + ball.getRadius(), cellSize);
        int y0 = toCellCoordinate(ball.getPos().y() - ball.getRadius(), cellSize);
        int y1 = toCellCoordinate(ball.getPos().y() + ball.getRadius(), cellSize);

        var cells = new ArrayList<Cell>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                cells.add(new Cell(x, y));
            }
        }
        return cells;
    }

    private int toCellCoordinate(double coordinate, double cellSize) {
        return (int) Math.floor(coordinate / cellSize);
    }

    record Pair(int firstIndex, int secondIndex) {}

    private record Cell(int x, int y) {}
}
