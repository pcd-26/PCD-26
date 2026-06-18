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
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

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
        stepInternal(board, elapsedMillis, false);
    }

    /**
     * Advances the board and returns a profiling snapshot for the executed
     * step. The board mutation semantics are identical to {@link #step(Board, long)}.
     *
     * @param board board to mutate
     * @param elapsedMillis elapsed time in milliseconds
     * @return per-phase timings and workload counters for the executed step
     */
    public StepProfile profileStep(Board board, long elapsedMillis) {
        return stepInternal(board, elapsedMillis, true);
    }

    private StepProfile stepInternal(Board board, long elapsedMillis, boolean profilingEnabled) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        ensureOpen();
        StepProfileAccumulator profile = profilingEnabled ? new StepProfileAccumulator(workers.length) : null;
        synchronized (board) {
            long remaining = elapsedMillis;
            while (remaining > 0) {
                long dt = Math.min(maxStepMillis, remaining);
                stepOnce(board, dt, profile);
                remaining -= dt;
            }
        }
        return profilingEnabled ? profile.toProfile() : null;
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

    private void stepOnce(Board board, long dt, StepProfileAccumulator profile) {
        var bounds = board.getBounds();
        var activeBalls = activeBalls(board);
        if (profile != null) {
            profile.activeBalls += activeBalls.size();
        }
        long integrationStart = profile == null ? 0 : System.nanoTime();
        runRanges(activeBalls.size(), (from, to, workerIndex) -> {
            long workerStart = profile == null ? 0 : System.nanoTime();
            for (int i = from; i < to; i++) {
                activeBalls.get(i).updateState(dt, bounds);
            }
            if (profile != null) {
                profile.integrationWorkerItems[workerIndex] += to - from;
                profile.integrationWorkerNanos[workerIndex] += System.nanoTime() - workerStart;
            }
        });
        if (profile != null) {
            profile.integrationNanos += System.nanoTime() - integrationStart;
        }

        long holeStart = profile == null ? 0 : System.nanoTime();
        board.applyHoleInteractions();
        if (profile != null) {
            profile.holeInteractionNanos += System.nanoTime() - holeStart;
        }

        var collisionBalls = board.getCollisionBalls();
        if (profile != null) {
            profile.collisionBalls += collisionBalls.size();
        }
        var detection = detectCollisionPairs(collisionBalls, profile);
        long resolutionStart = profile == null ? 0 : System.nanoTime();
        for (var pair : detection.pairs()) {
            var first = collisionBalls.get(pair.firstIndex());
            var second = collisionBalls.get(pair.secondIndex());
            board.recordCollision(first, second);
            Ball.resolveCollision(first, second);
        }
        if (profile != null) {
            profile.candidatePairs += detection.pairs().size();
            profile.collisionResolutionNanos += System.nanoTime() - resolutionStart;
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

    private DetectionResult detectCollisionPairs(List<Ball> balls, StepProfileAccumulator profile) {
        if (balls.size() < 2) {
            return new DetectionResult(List.of(), 0, 0);
        }

        double cellSize = computeCellSize(balls);
        @SuppressWarnings("unchecked")
        Map<Cell, List<Integer>>[] localGrids = new Map[workers.length];

        long localGridStart = profile == null ? 0 : System.nanoTime();
        runRanges(balls.size(), (from, to, workerIndex) -> {
            long workerStart = profile == null ? 0 : System.nanoTime();
            var localGrid = new HashMap<Cell, List<Integer>>();
            for (int i = from; i < to; i++) {
                for (var cell : occupiedCells(balls.get(i), cellSize)) {
                    localGrid.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(i);
                }
            }
            localGrids[workerIndex] = localGrid;
            if (profile != null) {
                profile.localGridWorkerItems[workerIndex] += to - from;
                profile.localGridWorkerNanos[workerIndex] += System.nanoTime() - workerStart;
            }
        });
        if (profile != null) {
            profile.localGridBuildNanos += System.nanoTime() - localGridStart;
        }

        long mergeStart = profile == null ? 0 : System.nanoTime();
        var mergedGrid = new HashMap<Cell, List<Integer>>();
        for (var localGrid : localGrids) {
            if (localGrid == null) {
                continue;
            }
            for (var entry : localGrid.entrySet()) {
                mergedGrid.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        if (profile != null) {
            profile.gridMergeNanos += System.nanoTime() - mergeStart;
        }

        long pairStart = profile == null ? 0 : System.nanoTime();
        Set<CollisionPair> pairs = new HashSet<>();
        int maxCellOccupancy = 0;
        for (var indexes : mergedGrid.values()) {
            maxCellOccupancy = Math.max(maxCellOccupancy, indexes.size());
            collectPairs(indexes, pairs);
        }

        var orderedPairs = new ArrayList<>(pairs);
        orderedPairs.sort(Comparator
                .comparingInt(CollisionPair::firstIndex)
                .thenComparingInt(CollisionPair::secondIndex));
        if (profile != null) {
            profile.pairCollectionNanos += System.nanoTime() - pairStart;
            profile.mergedCells += mergedGrid.size();
            profile.maxCellOccupancy = Math.max(profile.maxCellOccupancy, maxCellOccupancy);
        }
        return new DetectionResult(orderedPairs, mergedGrid.size(), maxCellOccupancy);
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

    private record DetectionResult(List<CollisionPair> pairs, int mergedCells, int maxCellOccupancy) {}

    /**
     * Immutable per-step profiling data for the threaded physics pipeline.
     *
     * @param activeBalls number of balls integrated across all internal sub-steps
     * @param collisionBalls number of balls considered for collision detection
     * @param candidatePairs number of candidate collision pairs generated
     * @param mergedCells number of populated cells in the merged spatial grid
     * @param maxCellOccupancy maximum number of balls registered in one cell
     * @param integrationMillis total integration time in milliseconds
     * @param holeInteractionMillis hole interaction time in milliseconds
     * @param localGridBuildMillis local per-worker grid-build time in milliseconds
     * @param gridMergeMillis merged-grid assembly time in milliseconds
     * @param pairCollectionMillis candidate-pair generation and sorting time in milliseconds
     * @param collisionResolutionMillis collision resolution time in milliseconds
     * @param integrationWorkerMillis per-worker integration time in milliseconds
     * @param localGridWorkerMillis per-worker local-grid build time in milliseconds
     * @param integrationWorkerItems per-worker ball counts integrated
     * @param localGridWorkerItems per-worker ball counts used for local-grid population
     */
    public record StepProfile(
            int activeBalls,
            int collisionBalls,
            int candidatePairs,
            int mergedCells,
            int maxCellOccupancy,
            double integrationMillis,
            double holeInteractionMillis,
            double localGridBuildMillis,
            double gridMergeMillis,
            double pairCollectionMillis,
            double collisionResolutionMillis,
            List<Double> integrationWorkerMillis,
            List<Double> localGridWorkerMillis,
            List<Integer> integrationWorkerItems,
            List<Integer> localGridWorkerItems) {}

    private static final class StepProfileAccumulator {

        private final long[] integrationWorkerNanos;
        private final long[] localGridWorkerNanos;
        private final int[] integrationWorkerItems;
        private final int[] localGridWorkerItems;
        private int activeBalls;
        private int collisionBalls;
        private int candidatePairs;
        private int mergedCells;
        private int maxCellOccupancy;
        private long integrationNanos;
        private long holeInteractionNanos;
        private long localGridBuildNanos;
        private long gridMergeNanos;
        private long pairCollectionNanos;
        private long collisionResolutionNanos;

        private StepProfileAccumulator(int workerCount) {
            integrationWorkerNanos = new long[workerCount];
            localGridWorkerNanos = new long[workerCount];
            integrationWorkerItems = new int[workerCount];
            localGridWorkerItems = new int[workerCount];
        }

        private StepProfile toProfile() {
            return new StepProfile(
                    activeBalls,
                    collisionBalls,
                    candidatePairs,
                    mergedCells,
                    maxCellOccupancy,
                    integrationNanos / NANOS_PER_MILLISECOND,
                    holeInteractionNanos / NANOS_PER_MILLISECOND,
                    localGridBuildNanos / NANOS_PER_MILLISECOND,
                    gridMergeNanos / NANOS_PER_MILLISECOND,
                    pairCollectionNanos / NANOS_PER_MILLISECOND,
                    collisionResolutionNanos / NANOS_PER_MILLISECOND,
                    toMillisList(integrationWorkerNanos),
                    toMillisList(localGridWorkerNanos),
                    toIntList(integrationWorkerItems),
                    toIntList(localGridWorkerItems));
        }

        private List<Double> toMillisList(long[] workerNanos) {
            var values = new ArrayList<Double>(workerNanos.length);
            for (var nanos : workerNanos) {
                values.add(nanos / NANOS_PER_MILLISECOND);
            }
            return List.copyOf(values);
        }

        private List<Integer> toIntList(int[] items) {
            var values = new ArrayList<Integer>(items.length);
            for (var itemCount : items) {
                values.add(itemCount);
            }
            return List.copyOf(values);
        }
    }
}
