package pcd.poool.model.physics.common;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure spatial-grid helpers shared by the collision detectors.
 *
 * <p>The helpers deliberately stay free of coordination logic so the threaded
 * and task-based engines can keep their own synchronization boundaries and
 * merge policies.
 */
public final class SpatialGridSupport {

    private SpatialGridSupport() {
    }

    /**
     * Computes the cell size for the spatial grid based on the smallest ball radius.
     *
     * @param balls the list of all balls currently on the board
     * @return the computed grid cell size as a double
     */
    public static double computeCellSize(List<Ball> balls) {
        double minRadius = balls.stream().mapToDouble(Ball::getRadius)
                .min()
                .orElse(PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
        return Math.max(minRadius * PhysicsDefaults.RADIUS_TO_DIAMETER, PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
    }

    /**
     * Determines all grid cells occupied by a given ball's bounding box.
     *
     * @param ball the ball entity to check
     * @param cellSize the size of each grid cell
     * @return a list of GridCells covered by the ball's bounds
     */
    public static List<GridCell> occupiedCells(Ball ball, double cellSize) {
        /*
         * A ball may be larger than the chosen cell size. Registering every
         * covered cell keeps candidate generation correct even when player and
         * small balls have different radii.
         */
        int x0 = toCellCoordinate(ball.getPos().x() - ball.getRadius(), cellSize);
        int x1 = toCellCoordinate(ball.getPos().x() + ball.getRadius(), cellSize);
        int y0 = toCellCoordinate(ball.getPos().y() - ball.getRadius(), cellSize);
        int y1 = toCellCoordinate(ball.getPos().y() + ball.getRadius(), cellSize);

        var cells = new ArrayList<GridCell>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                cells.add(new GridCell(x, y));
            }
        }
        return cells;
    }

    /**
     * Translates a continuous 1D coordinate to its discrete cell index.
     *
     * @param coordinate the absolute position coordinate (X or Y)
     * @param cellSize the cell size to divide by
     * @return the discrete cell index as an integer
     */
    public static int toCellCoordinate(double coordinate, double cellSize) {
        return (int) Math.floor(coordinate / cellSize);
    }

    /**
     * Checks if two grid cells are neighbors (adjacent horizontally, vertically, or diagonally).
     *
     * @param first the first grid cell
     * @param second the second grid cell
     * @return true if the cells are neighbors and not the same cell; false otherwise
     */
    public static boolean areNeighboringCells(GridCell first, GridCell second) {
        int dx = Math.abs(first.x() - second.x());
        int dy = Math.abs(first.y() - second.y());
        return dx <= 1 && dy <= 1 && (dx != 0 || dy != 0);
    }

    public record GridCell(int x, int y) implements Comparable<GridCell> {

        @Override
        public int compareTo(GridCell other) {
            int byX = Integer.compare(x, other.x);
            if (byX != 0) {
                return byX;
            }
            return Integer.compare(y, other.y);
        }
    }
}
