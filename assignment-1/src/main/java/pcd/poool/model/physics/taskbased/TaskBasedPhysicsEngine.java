package pcd.poool.model.physics.taskbased;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.Hole;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialCollisionDetector;
import pcd.poool.model.common.math.V2d;

/**
 * Task-based physics stepper for board state updates.
 *
 * <p>The engine keeps the same single-writer ownership rule used by the other
 * physics implementations: callers synchronize on the board for the whole
 * update, while internal task execution is limited to disjoint work ranges or
 * read-only coordination phases.
 */
public class TaskBasedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_POOL_SIZE = 1;

    private final ExecutorService executor;
    private final long maxStepMillis;
    private final int poolSize;
    private boolean closed;

    /**
     * Creates a task-based physics engine using the default pool size.
     */
    public TaskBasedPhysicsEngine() {
        this(defaultPoolSize());
    }

    /**
     * Creates a task-based physics engine.
     *
     * @param poolSize number of executor workers available for task phases
     */
    public TaskBasedPhysicsEngine(int poolSize) {
        this(poolSize, PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    /**
     * Creates a task-based physics engine.
     *
     * @param poolSize number of executor workers available for task phases
     * @param maxStepMillis maximum duration of one internal physics sub-step
     */
    public TaskBasedPhysicsEngine(int poolSize, long maxStepMillis) {
        if (poolSize < MIN_POOL_SIZE) {
            throw new IllegalArgumentException("poolSize must be >= 1");
        }
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        this.poolSize = poolSize;
        this.maxStepMillis = maxStepMillis;
        this.executor = Executors.newFixedThreadPool(poolSize, runnable -> {
            var thread = new Thread(runnable);
            thread.setName("poool-task-physics-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void step(Board board, long elapsedMillis) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        ensureOpen();
        synchronized (board) {
            ensureOpen();
            long remaining = elapsedMillis;
            while (remaining > 0) {
                long dt = Math.min(maxStepMillis, remaining);
                stepOnce(board, dt);
                remaining -= dt;
            }
        }
    }

    /**
     * Stops the executor and prevents further steps from being accepted.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
    }

    /**
     * Gets the configured executor pool size.
     *
     * @return number of executor workers available for task phases
     */
    public int poolSize() {
        return poolSize;
    }

    private void stepOnce(Board board, long dt) {
        var bounds = board.getBounds();
        var activeBalls = activeBalls(board);
        runRanges(activeBalls.size(), (from, to) -> {
            for (int i = from; i < to; i++) {
                activeBalls.get(i).ball().updateState(dt, bounds);
            }
            return null;
        });

        var holeInteractions = detectHoleInteractions(board.getHoles(), activeBalls);
        board.applyHoleInteractions(holeInteractions);

        var collisionBalls = board.getCollisionBalls();
        resolveCollisionsWithAccumulatedImpulses(board, collisionBalls, detectCollisionPairs(collisionBalls));
    }

    private List<ActiveBall> activeBalls(Board board) {
        var activeBalls = new ArrayList<ActiveBall>();
        var playerBall = board.getPlayerBallEntity();
        if (playerBall != null) {
            activeBalls.add(new ActiveBall(playerBall, BallRole.PLAYER));
        }
        var botBall = board.getBotBallEntity();
        if (botBall != null) {
            activeBalls.add(new ActiveBall(botBall, BallRole.BOT));
        }
        for (var ball : board.getSmallBallEntities()) {
            activeBalls.add(new ActiveBall(ball, BallRole.SMALL));
        }
        return activeBalls;
    }

    private Board.HoleInteractions detectHoleInteractions(List<Hole> holes, List<ActiveBall> activeBalls) {
        if (holes.isEmpty() || activeBalls.isEmpty()) {
            return new Board.HoleInteractions(false, false, List.of());
        }

        var results = runRanges(activeBalls.size(), (from, to) -> {
            boolean playerBallPocketed = false;
            boolean botBallPocketed = false;
            var pocketedSmallBalls = new ArrayList<Ball>();
            for (int i = from; i < to; i++) {
                var activeBall = activeBalls.get(i);
                if (isInsideAnyHole(activeBall.ball(), holes)) {
                    if (activeBall.role() == BallRole.PLAYER) {
                        playerBallPocketed = true;
                    } else if (activeBall.role() == BallRole.BOT) {
                        botBallPocketed = true;
                    } else {
                        pocketedSmallBalls.add(activeBall.ball());
                    }
                }
            }
            return new HoleTaskResult(playerBallPocketed, botBallPocketed, pocketedSmallBalls);
        });

        boolean playerBallPocketed = false;
        boolean botBallPocketed = false;
        var pocketedSmallBalls = new ArrayList<Ball>();
        for (var result : results) {
            playerBallPocketed |= result.playerBallPocketed();
            botBallPocketed |= result.botBallPocketed();
            pocketedSmallBalls.addAll(result.pocketedSmallBalls());
        }
        return new Board.HoleInteractions(playerBallPocketed, botBallPocketed, List.copyOf(pocketedSmallBalls));
    }

    List<SpatialCollisionDetector.Pair> detectCollisionPairs(List<Ball> balls) {
        if (balls.size() < 2) {
            return List.of();
        }

        double cellSize = computeCellSize(balls);
        var localGrids = runRanges(balls.size(), (from, to) -> {
            var localGrid = new java.util.HashMap<Cell, List<Integer>>();
            for (int i = from; i < to; i++) {
                for (var cell : occupiedCells(balls.get(i), cellSize)) {
                    localGrid.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(i);
                }
            }
            return localGrid;
        });

        var mergedGrid = new java.util.HashMap<Cell, List<Integer>>();
        for (var localGrid : localGrids) {
            if (localGrid == null) {
                continue;
            }
            for (var entry : localGrid.entrySet()) {
                mergedGrid.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
            }
        }

        var orderedCells = new ArrayList<>(mergedGrid.keySet());
        orderedCells.sort(Cell::compareTo);

        var pairs = new java.util.HashSet<SpatialCollisionDetector.Pair>();
        for (int i = 0; i < orderedCells.size(); i++) {
            var cell = orderedCells.get(i);
            collectPairs(mergedGrid.get(cell), pairs);
            for (int j = i + 1; j < orderedCells.size(); j++) {
                var otherCell = orderedCells.get(j);
                if (areNeighboringCells(cell, otherCell)) {
                    collectPairs(mergedGrid.get(cell), mergedGrid.get(otherCell), pairs);
                }
            }
        }

        var orderedPairs = new ArrayList<>(pairs);
        orderedPairs.sort(java.util.Comparator
                .comparingInt(SpatialCollisionDetector.Pair::firstIndex)
                .thenComparingInt(SpatialCollisionDetector.Pair::secondIndex));
        return orderedPairs;
    }

    private void resolveCollisionsWithAccumulatedImpulses(
            Board board,
            List<Ball> balls,
            List<SpatialCollisionDetector.Pair> pairs) {
        if (pairs.isEmpty()) {
            return;
        }

        var localAccumulators = runRanges(pairs.size(), (from, to) -> {
            var accumulator = new CollisionAccumulator(balls.size());
            for (int i = from; i < to; i++) {
                accumulator.add(computeCollisionContribution(balls, pairs.get(i)));
            }
            return accumulator;
        });

        var merged = new CollisionAccumulator(balls.size());
        for (var accumulator : localAccumulators) {
            if (accumulator != null) {
                merged.merge(accumulator);
            }
        }

        for (var pair : merged.contactPairs) {
            board.recordCollision(balls.get(pair.firstIndex()), balls.get(pair.secondIndex()));
        }

        runRanges(balls.size(), (from, to) -> {
            for (int i = from; i < to; i++) {
                balls.get(i).translate(new V2d(merged.positionDeltaX[i], merged.positionDeltaY[i]));
                balls.get(i).addVelocity(new V2d(merged.velocityDeltaX[i], merged.velocityDeltaY[i]));
            }
            return null;
        });
    }

    private CollisionContribution computeCollisionContribution(List<Ball> balls, SpatialCollisionDetector.Pair pair) {
        var a = balls.get(pair.firstIndex());
        var b = balls.get(pair.secondIndex());

        double dx = b.getPos().x() - a.getPos().x();
        double dy = b.getPos().y() - a.getPos().y();
        double dist = Math.hypot(dx, dy);
        double minD = a.getRadius() + b.getRadius();

        if (dist >= minD) {
            return null;
        }
        if (dist <= PhysicsDefaults.COINCIDENT_CENTER_EPSILON) {
            dx = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
            dy = 0.0;
            dist = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
        }

        double nx = dx / dist;
        double ny = dy / dist;
        double totalMass = a.getMass() + b.getMass();
        double overlap = minD - dist;
        double firstPositionCorrection = overlap * (b.getMass() / totalMass);
        double secondPositionCorrection = overlap * (a.getMass() / totalMass);

        double firstVelocityDeltaX = 0.0;
        double firstVelocityDeltaY = 0.0;
        double secondVelocityDeltaX = 0.0;
        double secondVelocityDeltaY = 0.0;
        double relativeVelocityX = b.getVel().x() - a.getVel().x();
        double relativeVelocityY = b.getVel().y() - a.getVel().y();
        double relativeVelocityAlongNormal = relativeVelocityX * nx + relativeVelocityY * ny;
        if (relativeVelocityAlongNormal <= 0) {
            double impulse = -(1 + PhysicsDefaults.RESTITUTION_FACTOR) * relativeVelocityAlongNormal
                    / (1.0 / a.getMass() + 1.0 / b.getMass());
            firstVelocityDeltaX = -(impulse / a.getMass()) * nx;
            firstVelocityDeltaY = -(impulse / a.getMass()) * ny;
            secondVelocityDeltaX = (impulse / b.getMass()) * nx;
            secondVelocityDeltaY = (impulse / b.getMass()) * ny;
        }

        return new CollisionContribution(
                pair,
                -nx * firstPositionCorrection,
                -ny * firstPositionCorrection,
                firstVelocityDeltaX,
                firstVelocityDeltaY,
                nx * secondPositionCorrection,
                ny * secondPositionCorrection,
                secondVelocityDeltaX,
                secondVelocityDeltaY);
    }

    private <T> List<T> runRanges(int itemCount, RangeTask<T> rangeTask) {
        if (itemCount == 0) {
            return List.of();
        }

        var ranges = buildRangeChunks(itemCount);
        var tasks = new ArrayList<Callable<T>>(ranges.size());
        for (var range : ranges) {
            tasks.add(() -> {
                return rangeTask.run(range.fromInclusive(), range.toExclusive());
            });
        }
        return invokeAll(tasks);
    }

    private <T> List<T> invokeAll(List<Callable<T>> tasks) {
        try {
            List<Future<T>> futures = executor.invokeAll(tasks);
            var results = new ArrayList<T>(futures.size());
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("task-based physics step interrupted", ex);
        } catch (ExecutionException ex) {
            throw rethrowTaskFailure(ex.getCause());
        }
    }

    private boolean isInsideAnyHole(Ball ball, List<Hole> holes) {
        for (var hole : holes) {
            if (hole.contains(ball.getPos())) {
                return true;
            }
        }
        return false;
    }

    private double computeCellSize(List<Ball> balls) {
        double minRadius = balls.stream().mapToDouble(Ball::getRadius)
                .min()
                .orElse(PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
        return Math.max(minRadius * PhysicsDefaults.RADIUS_TO_DIAMETER, PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
    }

    private void collectPairs(List<Integer> indexes, java.util.Set<SpatialCollisionDetector.Pair> pairs) {
        if (indexes == null) {
            return;
        }
        for (int i = 0; i < indexes.size() - 1; i++) {
            for (int j = i + 1; j < indexes.size(); j++) {
                pairs.add(new SpatialCollisionDetector.Pair(
                        Math.min(indexes.get(i), indexes.get(j)),
                        Math.max(indexes.get(i), indexes.get(j))));
            }
        }
    }

    private void collectPairs(
            List<Integer> firstIndexes,
            List<Integer> secondIndexes,
            java.util.Set<SpatialCollisionDetector.Pair> pairs) {
        if (firstIndexes == null || secondIndexes == null) {
            return;
        }
        for (var firstIndex : firstIndexes) {
            for (var secondIndex : secondIndexes) {
                if (firstIndex.equals(secondIndex)) {
                    continue;
                }
                pairs.add(new SpatialCollisionDetector.Pair(
                        Math.min(firstIndex, secondIndex),
                        Math.max(firstIndex, secondIndex)));
            }
        }
    }

    private List<Cell> occupiedCells(Ball ball, double cellSize) {
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

    private boolean areNeighboringCells(Cell first, Cell second) {
        int dx = Math.abs(first.x() - second.x());
        int dy = Math.abs(first.y() - second.y());
        return dx <= 1 && dy <= 1 && (dx != 0 || dy != 0);
    }

    private List<RangeChunk> buildRangeChunks(int itemCount) {
        int workerCount = Math.min(poolSize, itemCount);
        int baseChunk = itemCount / workerCount;
        int remainder = itemCount % workerCount;
        var chunks = new ArrayList<RangeChunk>(workerCount);
        int from = 0;
        for (int i = 0; i < workerCount; i++) {
            int chunkSize = baseChunk + (i < remainder ? 1 : 0);
            int to = from + chunkSize;
            chunks.add(new RangeChunk(from, to));
            from = to;
        }
        return List.copyOf(chunks);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("task-based physics engine is closed");
        }
    }

    private RuntimeException rethrowTaskFailure(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("task-based physics step failed", cause);
    }

    private static int defaultPoolSize() {
        return Math.max(MIN_POOL_SIZE, Runtime.getRuntime().availableProcessors());
    }

    @FunctionalInterface
    private interface RangeTask<T> {

        T run(int fromInclusive, int toExclusive);
    }

    private record RangeChunk(int fromInclusive, int toExclusive) {}

    private record HoleTaskResult(
            boolean playerBallPocketed,
            boolean botBallPocketed,
            List<Ball> pocketedSmallBalls) {}

    private record ActiveBall(Ball ball, BallRole role) {}

    private record Cell(int x, int y) implements Comparable<Cell> {

        @Override
        public int compareTo(Cell other) {
            int byX = Integer.compare(x, other.x);
            if (byX != 0) {
                return byX;
            }
            return Integer.compare(y, other.y);
        }
    }

    private enum BallRole {
        PLAYER,
        BOT,
        SMALL
    }

    private record CollisionContribution(
            SpatialCollisionDetector.Pair pair,
            double firstPositionDeltaX,
            double firstPositionDeltaY,
            double firstVelocityDeltaX,
            double firstVelocityDeltaY,
            double secondPositionDeltaX,
            double secondPositionDeltaY,
            double secondVelocityDeltaX,
            double secondVelocityDeltaY) {}

    private static final class CollisionAccumulator {

        private final double[] positionDeltaX;
        private final double[] positionDeltaY;
        private final double[] velocityDeltaX;
        private final double[] velocityDeltaY;
        private final List<SpatialCollisionDetector.Pair> contactPairs;

        private CollisionAccumulator(int ballCount) {
            positionDeltaX = new double[ballCount];
            positionDeltaY = new double[ballCount];
            velocityDeltaX = new double[ballCount];
            velocityDeltaY = new double[ballCount];
            contactPairs = new ArrayList<>();
        }

        private void add(CollisionContribution contribution) {
            if (contribution == null) {
                return;
            }
            var pair = contribution.pair();
            positionDeltaX[pair.firstIndex()] += contribution.firstPositionDeltaX();
            positionDeltaY[pair.firstIndex()] += contribution.firstPositionDeltaY();
            velocityDeltaX[pair.firstIndex()] += contribution.firstVelocityDeltaX();
            velocityDeltaY[pair.firstIndex()] += contribution.firstVelocityDeltaY();
            positionDeltaX[pair.secondIndex()] += contribution.secondPositionDeltaX();
            positionDeltaY[pair.secondIndex()] += contribution.secondPositionDeltaY();
            velocityDeltaX[pair.secondIndex()] += contribution.secondVelocityDeltaX();
            velocityDeltaY[pair.secondIndex()] += contribution.secondVelocityDeltaY();
            contactPairs.add(pair);
        }

        private void merge(CollisionAccumulator other) {
            for (int i = 0; i < positionDeltaX.length; i++) {
                positionDeltaX[i] += other.positionDeltaX[i];
                positionDeltaY[i] += other.positionDeltaY[i];
                velocityDeltaX[i] += other.velocityDeltaX[i];
                velocityDeltaY[i] += other.velocityDeltaY[i];
            }
            contactPairs.addAll(other.contactPairs);
        }
    }
}
