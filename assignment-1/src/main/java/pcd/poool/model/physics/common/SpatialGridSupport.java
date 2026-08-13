package pcd.poool.model.physics.common;

import java.util.ArrayList;
import java.util.List;

/** Spatial-grid helpers shared by the collision detectors. */
public final class SpatialGridSupport {

    private SpatialGridSupport() {
    }

    /** Computes the cell size for the current set of balls. */
    public static double computeCellSize(List<Ball> balls) {
        double minRadius = balls.stream().mapToDouble(Ball::getRadius)
                .min()
                .orElse(PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
        return Math.max(minRadius * PhysicsDefaults.RADIUS_TO_DIAMETER, PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
    }

    /** Returns only the grid cells occupied by the ball's bounding box. */
    public static List<GridCell> occupiedCells(Ball ball, double cellSize) {
        // Only the cells actually covered by the ball are created here.
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

    /** Maps a continuous coordinate to a grid cell index. */
    public static int toCellCoordinate(double coordinate, double cellSize) {
        return (int) Math.floor(coordinate / cellSize);
    }

    /** Returns whether two cells are adjacent, including diagonals. */
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
