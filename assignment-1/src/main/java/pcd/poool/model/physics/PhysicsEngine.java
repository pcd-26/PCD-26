package pcd.poool.model.physics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic physics stepper for board state updates.
 */
public class PhysicsEngine {

    private static final long DEFAULT_MAX_STEP_MS = 16;
    private final long maxStepMillis;

    public PhysicsEngine() {
        this(DEFAULT_MAX_STEP_MS);
    }

    public PhysicsEngine(long maxStepMillis) {
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        this.maxStepMillis = maxStepMillis;
    }

    public void step(Board board, long elapsedMillis) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        synchronized (board) {
            long remaining = elapsedMillis;
            while (remaining > 0) {
                long dt = Math.min(maxStepMillis, remaining);
                stepOnce(board, dt);
                remaining -= dt;
            }
        }
    }

    private void stepOnce(Board board, long dt) {
        var bounds = board.getBounds();
        if (board.getPlayerBallEntity() != null) {
            board.getPlayerBallEntity().updateState(dt, bounds);
        }
        for (var ball : board.getSmallBallEntities()) {
            ball.updateState(dt, bounds);
        }

        board.applyHoleInteractions();

        var allBalls = board.getCollisionBalls();
        for (var pair : detectCollisionPairs(allBalls)) {
            Ball.resolveCollision(allBalls.get(pair.firstIndex()), allBalls.get(pair.secondIndex()));
        }
    }

    List<Pair> detectCollisionPairs(List<Ball> balls) {
        if (balls.size() < 2) {
            return List.of();
        }

        double minRadius = balls.stream().mapToDouble(Ball::getRadius).min().orElse(0.01);
        double cellSize = Math.max(minRadius * 2.0, 0.0001);
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
        int x0 = (int) Math.floor((ball.getPos().x() - ball.getRadius()) / cellSize);
        int x1 = (int) Math.floor((ball.getPos().x() + ball.getRadius()) / cellSize);
        int y0 = (int) Math.floor((ball.getPos().y() - ball.getRadius()) / cellSize);
        int y1 = (int) Math.floor((ball.getPos().y() + ball.getRadius()) / cellSize);

        var cells = new ArrayList<Cell>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                cells.add(new Cell(x, y));
            }
        }
        return cells;
    }

    record Pair(int firstIndex, int secondIndex) {}

    private record Cell(int x, int y) {}
}
