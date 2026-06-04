package pcd.poool.model.physics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform-threaded physics stepper for large Poool configurations.
 *
 * <p>The engine keeps board mutation under the same single-writer ownership
 * rule used by the sequential engine: callers enter {@link #step(Board, long)}
 * and the method synchronizes on the board for the whole tick. Inside that
 * critical section, long-lived worker threads perform independent computation
 * on disjoint ball chunks. The controller thread then merges worker results and
 * resolves collisions in a deterministic order.
 *
 * <p>The current parallel phases are:
 *
 * <ol>
 *   <li>ball integration: friction, movement, and boundary constraints;</li>
 *   <li>spatial-grid population for broad-phase collision detection;</li>
 *   <li>deterministic merge/deduplication of candidate pairs;</li>
 *   <li>serial collision resolution and game-event recording.</li>
 * </ol>
 */
public class ThreadedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_WORKER_COUNT = 1;

    private final long maxStepMillis;
    private final PhysicsWorker[] workers;
    private boolean closed;

    /**
     * Creates a threaded physics engine using a CPU-oriented default worker
     * count.
     */
    public ThreadedPhysicsEngine() {
        this(defaultWorkerCount());
    }

    /**
     * Creates a threaded physics engine.
     *
     * @param workerCount number of long-lived worker platform threads
     */
    public ThreadedPhysicsEngine(int workerCount) {
        this(workerCount, PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    /**
     * Creates a threaded physics engine.
     *
     * @param workerCount number of long-lived worker platform threads
     * @param maxStepMillis maximum duration of one internal physics sub-step
     */
    public ThreadedPhysicsEngine(int workerCount, long maxStepMillis) {
        if (workerCount < MIN_WORKER_COUNT) {
            throw new IllegalArgumentException("workerCount must be >= 1");
        }
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        this.maxStepMillis = maxStepMillis;
        workers = new PhysicsWorker[workerCount];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new PhysicsWorker("poool-physics-worker-" + i);
        }
    }

    @Override
    public void step(Board board, long elapsedMillis) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        ensureOpen();
        synchronized (board) {
            long remaining = elapsedMillis;
            while (remaining > 0) {
                long dt = Math.min(maxStepMillis, remaining);
                stepOnce(board, dt);
                remaining -= dt;
            }
        }
    }

    /**
     * Stops all worker platform threads.
     */
    @Override
    public void close() {
        closed = true;
        for (var worker : workers) {
            worker.close();
        }
    }

    /**
     * Gets the number of worker threads owned by this engine.
     *
     * @return number of worker threads owned by this engine
     */
    public int workerCount() {
        return workers.length;
    }

    private void stepOnce(Board board, long dt) {
        var bounds = board.getBounds();
        var activeBalls = activeBalls(board);
        runRanges(activeBalls.size(), (from, to, workerIndex) -> {
            for (int i = from; i < to; i++) {
                activeBalls.get(i).updateState(dt, bounds);
            }
        });

        board.applyHoleInteractions();

        var collisionBalls = board.getCollisionBalls();
        for (var pair : detectCollisionPairs(collisionBalls)) {
            var first = collisionBalls.get(pair.firstIndex());
            var second = collisionBalls.get(pair.secondIndex());
            board.recordCollision(first, second);
            Ball.resolveCollision(first, second);
        }
    }

    private List<Ball> activeBalls(Board board) {
        var activeBalls = new ArrayList<Ball>();
        if (board.getPlayerBallEntity() != null) {
            activeBalls.add(board.getPlayerBallEntity());
        }
        if (board.getBotBallEntity() != null) {
            activeBalls.add(board.getBotBallEntity());
        }
        activeBalls.addAll(board.getSmallBallEntities());
        return activeBalls;
    }

    private List<CollisionPair> detectCollisionPairs(List<Ball> balls) {
        if (balls.size() < 2) {
            return List.of();
        }

        double cellSize = computeCellSize(balls);
        @SuppressWarnings("unchecked")
        Map<Cell, List<Integer>>[] localGrids = new Map[workers.length];

        runRanges(balls.size(), (from, to, workerIndex) -> {
            var localGrid = new HashMap<Cell, List<Integer>>();
            for (int i = from; i < to; i++) {
                for (var cell : occupiedCells(balls.get(i), cellSize)) {
                    localGrid.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(i);
                }
            }
            localGrids[workerIndex] = localGrid;
        });

        var mergedGrid = new HashMap<Cell, List<Integer>>();
        for (var localGrid : localGrids) {
            if (localGrid == null) {
                continue;
            }
            for (var entry : localGrid.entrySet()) {
                mergedGrid.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
            }
        }

        Set<CollisionPair> pairs = new HashSet<>();
        for (var indexes : mergedGrid.values()) {
            collectPairs(indexes, pairs);
        }

        var orderedPairs = new ArrayList<>(pairs);
        orderedPairs.sort(Comparator
                .comparingInt(CollisionPair::firstIndex)
                .thenComparingInt(CollisionPair::secondIndex));
        return orderedPairs;
    }

    private double computeCellSize(List<Ball> balls) {
        double minRadius = balls.stream().mapToDouble(Ball::getRadius)
                .min()
                .orElse(PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
        return Math.max(minRadius * PhysicsDefaults.RADIUS_TO_DIAMETER, PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
    }

    private void collectPairs(List<Integer> indexes, Set<CollisionPair> pairs) {
        for (int i = 0; i < indexes.size() - 1; i++) {
            for (int j = i + 1; j < indexes.size(); j++) {
                pairs.add(new CollisionPair(
                        Math.min(indexes.get(i), indexes.get(j)),
                        Math.max(indexes.get(i), indexes.get(j))));
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

    private void runRanges(int itemCount, RangeTask rangeTask) {
        if (itemCount == 0) {
            return;
        }
        int workerCount = Math.min(workers.length, itemCount);
        var completion = new WorkerCompletionMonitor(workerCount);
        int baseChunk = itemCount / workerCount;
        int remainder = itemCount % workerCount;
        int from = 0;
        for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
            int chunkSize = baseChunk + (workerIndex < remainder ? 1 : 0);
            int start = from;
            int end = start + chunkSize;
            int assignedWorker = workerIndex;
            workers[workerIndex].assign(() -> rangeTask.run(start, end, assignedWorker), completion);
            from = end;
        }
        completion.await();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("threaded physics engine is closed");
        }
    }

    private static int defaultWorkerCount() {
        return Math.max(MIN_WORKER_COUNT, Runtime.getRuntime().availableProcessors() - 1);
    }

    @FunctionalInterface
    private interface RangeTask {

        void run(int fromInclusive, int toExclusive, int workerIndex);
    }

    private record Cell(int x, int y) {}

    private record CollisionPair(int firstIndex, int secondIndex) {}
}
