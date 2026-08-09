package pcd.poool.model.physics.taskbased;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialGridSupport;
import pcd.poool.model.physics.common.SpatialGridSupport.GridCell;

/** Executor Framework physics engine. */
public class TaskBasedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_WORKER_COUNT = 1;
    private static final int MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY = 256;

    private final long maxStepMillis;
    private final int poolSize;
    private final ExecutorService executor;
    private boolean closed;

    public TaskBasedPhysicsEngine() {
        this(defaultPoolSize());
    }

    public TaskBasedPhysicsEngine(int poolSize) {
        this(poolSize, PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    public TaskBasedPhysicsEngine(int poolSize, long maxStepMillis) {
        if (poolSize < MIN_WORKER_COUNT) {
            throw new IllegalArgumentException("poolSize must be >= 1");
        }
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        // Create a fixed executor so task-based work always runs on the same pool size.
        this.poolSize = poolSize;
        this.executor = Executors.newFixedThreadPool(poolSize, runnable -> {
            var thread = new Thread(runnable, "poool-task-physics-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.maxStepMillis = maxStepMillis;
    }

    @Override
    public void step(Board board, long elapsedMillis) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        ensureOpen();
        // Keep the board locked while the step is being processed.
        synchronized (board) {
            // Chop the elapsed time into fixed-size physics ticks.
            long remaining = elapsedMillis;
            while (remaining > 0) {
                long dt = Math.min(maxStepMillis, remaining);
                stepOnce(board, dt);
                remaining -= dt;
            }
        }
    }

    public int poolSize() {
        return poolSize;
    }

    @Override
    public void close() {
        closed = true;
        // Shut down the pool after the engine is no longer used.
        executor.shutdown();
    }

    private void stepOnce(Board board, long dt) {
        // Move the active balls first, keeping the motion phase separate from collision handling.
        advanceActiveBalls(board, dt);

        // Hole interactions may pocket balls or otherwise alter the active set.
        board.applyHoleInteractions();

        // Re-read the active set because the previous phase may have removed balls.
        var activeBallsForCollisionPhase = board.getActiveBalls();
        if (activeBallsForCollisionPhase.size() < 2) {
            return;
        }

        // Detect candidate collisions first, then resolve the accumulated deltas.
        SparseCollisionDeltaAccumulator detectedCollisionDeltas = detectCandidateCollisions(board, activeBallsForCollisionPhase);
        resolveCollisions(activeBallsForCollisionPhase, detectedCollisionDeltas);
    }

    private void advanceActiveBalls(Board board, long dt) {
        var bounds = board.getBounds();

        // Motion phase: update all active balls so positions and velocities move forward.
        var activeBalls = board.getActiveBalls();
        int activeBallCount = activeBalls.size();
        int activeTaskCount = activeBallCount < 256 ? 1 : Math.min(poolSize, activeBallCount);
        if (activeTaskCount <= 1) {
            // Small batches stay sequential because task creation would cost more than it saves.
            for (int i = 0; i < activeBallCount; i++) {
                activeBalls.get(i).updateState(dt, bounds);
            }
            return;
        }

        // Submit one task per contiguous chunk of active balls.
        int baseSize = activeBallCount / activeTaskCount;
        int remainder = activeBallCount % activeTaskCount;
        var futures = new ArrayList<Future<?>>(activeTaskCount);
        int from = 0;
        for (int workerIndex = 0; workerIndex < activeTaskCount; workerIndex++) {
            int size = baseSize + (workerIndex < remainder ? 1 : 0);
            int rangeStart = from;
            int rangeEnd = rangeStart + size;
            futures.add(executor.submit(() -> {
                for (int i = rangeStart; i < rangeEnd; i++) {
                    activeBalls.get(i).updateState(dt, bounds);
                }
            }));
            from = rangeEnd;
        }
        // Wait until every submitted task has finished.
        awaitAll(futures);
    }

    private SparseCollisionDeltaAccumulator detectCandidateCollisions(Board board, List<Ball> balls) {
        double cellSize = computeOwnershipCellSize(balls); // Grid size based on the largest ball.
        Map<GridCell, IntBag>[] workerGrids = buildWorkerGrids(balls, cellSize); // Local grids per task.
        var combinedGrid = mergeWorkerGrids(workerGrids); // Merge the local grids.
        var orderedCellBuckets = orderCellBuckets(combinedGrid); // Keep cell processing deterministic.
        return collectCandidateCollisionDeltas(board, balls, combinedGrid, orderedCellBuckets);
    }

    private void resolveCollisions(List<Ball> balls, SparseCollisionDeltaAccumulator accumulatedCollisionDeltas) {
        // Resolution phase: apply all accumulated positional and velocity deltas.
        if (accumulatedCollisionDeltas.touchedCount() == 0) {
            return;
        }
        // Small batches are cheaper to apply sequentially than to split again.
        if (accumulatedCollisionDeltas.touchedCount() < MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY) {
            for (int i = 0; i < accumulatedCollisionDeltas.touchedCount(); i++) {
                int ballIndex = accumulatedCollisionDeltas.touchedIndex(i);
                balls.get(ballIndex).translate(new V2d(accumulatedCollisionDeltas.positionDeltaX(ballIndex), accumulatedCollisionDeltas.positionDeltaY(ballIndex)));
                balls.get(ballIndex).addVelocity(new V2d(accumulatedCollisionDeltas.velocityDeltaX(ballIndex), accumulatedCollisionDeltas.velocityDeltaY(ballIndex)));
            }
            return;
        }
        // Reapply larger delta sets in parallel.
        int touchedCount = accumulatedCollisionDeltas.touchedCount();
        int applyTaskCount = Math.min(poolSize, touchedCount);
        int baseSize = touchedCount / applyTaskCount;
        int remainder = touchedCount % applyTaskCount;
        var futures = new ArrayList<Future<?>>(applyTaskCount);
        int from = 0;
        for (int workerIndex = 0; workerIndex < applyTaskCount; workerIndex++) {
            int size = baseSize + (workerIndex < remainder ? 1 : 0);
            int rangeStart = from;
            int rangeEnd = rangeStart + size;
            futures.add(executor.submit(() -> {
                for (int i = rangeStart; i < rangeEnd; i++) {
                    int ballIndex = accumulatedCollisionDeltas.touchedIndex(i);
                    balls.get(ballIndex).translate(new V2d(accumulatedCollisionDeltas.positionDeltaX(ballIndex), accumulatedCollisionDeltas.positionDeltaY(ballIndex)));
                    balls.get(ballIndex).addVelocity(new V2d(accumulatedCollisionDeltas.velocityDeltaX(ballIndex), accumulatedCollisionDeltas.velocityDeltaY(ballIndex)));
                }
            }));
            from = rangeEnd;
        }
        // Wait until every delta task has finished.
        awaitAll(futures);
    }

    private Map<GridCell, IntBag>[] buildWorkerGrids(List<Ball> balls, double cellSize) {
        // Build one local grid per worker to avoid write contention.
        @SuppressWarnings("unchecked")
        Map<GridCell, IntBag>[] workerGrids = new Map[poolSize];
        int ballCount = balls.size();
        int gridTaskCount = Math.min(poolSize, ballCount);
        if (gridTaskCount <= 1) {
            // Tiny collections are cheaper to partition directly on the calling thread.
            var localGrid = new HashMap<GridCell, IntBag>();
            for (int i = 0; i < ballCount; i++) {
                GridCell assignedCellForBall = gridCellForBallCenter(balls.get(i), cellSize);
                localGrid.computeIfAbsent(assignedCellForBall, ignored -> new IntBag()).add(i);
            }
            if (ballCount > 0) {
                workerGrids[0] = localGrid;
            }
            return workerGrids;
        }

        // Build one local grid per submitted task.
        int baseSize = ballCount / gridTaskCount;
        int remainder = ballCount % gridTaskCount;
        var futures = new ArrayList<Future<?>>(gridTaskCount);
        int from = 0;
        for (int workerIndex = 0; workerIndex < gridTaskCount; workerIndex++) {
            int size = baseSize + (workerIndex < remainder ? 1 : 0);
            int rangeStart = from;
            int rangeEnd = rangeStart + size;
            int assignedWorker = workerIndex;
            futures.add(executor.submit(() -> {
                var localGrid = new HashMap<GridCell, IntBag>();
                for (int i = rangeStart; i < rangeEnd; i++) {
                    GridCell assignedCellForBall = gridCellForBallCenter(balls.get(i), cellSize);
                    localGrid.computeIfAbsent(assignedCellForBall, ignored -> new IntBag()).add(i);
                }
                workerGrids[assignedWorker] = localGrid;
            }));
            from = rangeEnd;
        }
        // Wait until every local grid has been produced.
        awaitAll(futures);
        return workerGrids;
    }

    private HashMap<GridCell, IntBag> mergeWorkerGrids(Map<GridCell, IntBag>[] workerGrids) {
        // Merge the local grids into one deterministic global view.
        var combinedGrid = new HashMap<GridCell, IntBag>();
        for (var localGrid : workerGrids) {
            if (localGrid == null) {
                continue;
            }
            for (var entry : localGrid.entrySet()) {
                combinedGrid.computeIfAbsent(entry.getKey(), ignored -> new IntBag()).addAll(entry.getValue());
            }
        }
        return combinedGrid;
    }

    private ArrayList<CellBucket> orderCellBuckets(Map<GridCell, IntBag> combinedGrid) {
        // Sort cells so the collision pass stays stable across runs.
        var orderedCellBuckets = new ArrayList<CellBucket>(combinedGrid.size());
        for (var entry : combinedGrid.entrySet()) {
            orderedCellBuckets.add(new CellBucket(entry.getKey(), entry.getValue()));
        }
        orderedCellBuckets.sort((first, second) -> first.cell().compareTo(second.cell()));
        return orderedCellBuckets;
    }

    private SparseCollisionDeltaAccumulator collectCandidateCollisionDeltas(
            Board board,
            List<Ball> balls,
            Map<GridCell, IntBag> combinedGrid,
            ArrayList<CellBucket> orderedCellBuckets) {
        // Each worker collects the deltas and contact pairs for the cells it owns.
        var collisionDeltasByWorker = new SparseCollisionDeltaAccumulator[Math.min(poolSize, orderedCellBuckets.size())];
        @SuppressWarnings("unchecked")
        List<CollisionPair>[] collisionPairsByWorker = new List[Math.min(poolSize, orderedCellBuckets.size())];
        int cellCount = orderedCellBuckets.size();
        int cellTaskCount = Math.min(poolSize, cellCount);
        if (cellTaskCount <= 1) {
            // A tiny collision set is processed directly to avoid worker overhead.
            var deltaAccumulator = new SparseCollisionDeltaAccumulator(balls.size());
            var pairAccumulator = new ArrayList<CollisionPair>();
            for (int i = 0; i < cellCount; i++) {
                resolveOwnedCell(orderedCellBuckets.get(i), combinedGrid, balls, deltaAccumulator, pairAccumulator);
            }
            if (cellCount > 0) {
                collisionDeltasByWorker[0] = deltaAccumulator;
                collisionPairsByWorker[0] = pairAccumulator;
            }
        } else {
            // Each worker resolves the detection work for the cell slice it owns.
            int baseSize = cellCount / cellTaskCount;
            int remainder = cellCount % cellTaskCount;
            var futures = new ArrayList<Future<?>>(cellTaskCount);
            int from = 0;
            for (int workerIndex = 0; workerIndex < cellTaskCount; workerIndex++) {
                int size = baseSize + (workerIndex < remainder ? 1 : 0);
                int rangeStart = from;
                int rangeEnd = rangeStart + size;
                int assignedWorker = workerIndex;
                futures.add(executor.submit(() -> {
                    var deltaAccumulator = new SparseCollisionDeltaAccumulator(balls.size());
                    var pairAccumulator = new ArrayList<CollisionPair>();
                    for (int i = rangeStart; i < rangeEnd; i++) {
                        resolveOwnedCell(orderedCellBuckets.get(i), combinedGrid, balls, deltaAccumulator, pairAccumulator);
                    }
                    collisionDeltasByWorker[assignedWorker] = deltaAccumulator;
                    collisionPairsByWorker[assignedWorker] = pairAccumulator;
                }));
                from = rangeEnd;
            }
            // Wait until every collision task has finished.
            awaitAll(futures);
        }

        // Merge all worker deltas into a single batch to apply at the end.
        var combinedDeltas = new SparseCollisionDeltaAccumulator(balls.size());
        int pairCount = 0;
        for (int i = 0; i < collisionDeltasByWorker.length; i++) {
            if (collisionDeltasByWorker[i] != null) {
                combinedDeltas.merge(collisionDeltasByWorker[i]);
            }
            if (collisionPairsByWorker[i] != null) {
                pairCount += collisionPairsByWorker[i].size();
            }
        }

        // Collect all confirmed collision candidates, sort them, and report each one once.
        var contactPairs = new ArrayList<CollisionPair>(pairCount);
        for (var localPairBag : collisionPairsByWorker) {
            if (localPairBag == null) {
                continue;
            }
            contactPairs.addAll(localPairBag);
        }
        contactPairs.sort((first, second) -> {
            int byFirst = Integer.compare(first.firstIndex(), second.firstIndex());
            if (byFirst != 0) {
                return byFirst;
            }
            return Integer.compare(first.secondIndex(), second.secondIndex());
        });
        for (var pair : contactPairs) {
            board.recordCollision(balls.get(pair.firstIndex()), balls.get(pair.secondIndex()));
        }

        return combinedDeltas;
    }

    private void resolveOwnedCell(
            CellBucket bucket,
            Map<GridCell, IntBag> mergedGrid,
        List<Ball> balls,
        SparseCollisionDeltaAccumulator deltas,
        List<CollisionPair> contactPairs) {
        IntBag indexes = bucket.indexes();
        // Detection phase: check balls that already live in the same cell.
        collectPairsWithinBag(indexes, balls, deltas, contactPairs);
        // Detection phase: check the neighboring cells that belong to this cell's ownership region.
        collectCrossPairs(indexes, mergedGrid.get(new GridCell(bucket.cell().x() + 1, bucket.cell().y() - 1)),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new GridCell(bucket.cell().x() + 1, bucket.cell().y())),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new GridCell(bucket.cell().x() + 1, bucket.cell().y() + 1)),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new GridCell(bucket.cell().x(), bucket.cell().y() + 1)),
                balls, deltas, contactPairs);
    }

    private void collectPairsWithinBag(
            IntBag indexes,
        List<Ball> balls,
        SparseCollisionDeltaAccumulator deltas,
        List<CollisionPair> contactPairs) {
        // Detection phase: compare every pair in the cell.
        for (int i = 0; i < indexes.size() - 1; i++) {
            int first = indexes.get(i);
            for (int j = i + 1; j < indexes.size(); j++) {
                addContributionIfColliding(balls, first, indexes.get(j), deltas, contactPairs);
            }
        }
    }

    private void collectCrossPairs(
            IntBag firstBag,
            IntBag secondBag,
        List<Ball> balls,
        SparseCollisionDeltaAccumulator deltas,
        List<CollisionPair> contactPairs) {
        if (secondBag == null) {
            return;
        }
        // Detection phase: compare the current cell with one neighboring cell at a time.
        for (int i = 0; i < firstBag.size(); i++) {
            int first = firstBag.get(i);
            for (int j = 0; j < secondBag.size(); j++) {
                addContributionIfColliding(balls, first, secondBag.get(j), deltas, contactPairs);
            }
        }
    }

    private void addContributionIfColliding(
        List<Ball> balls,
        int first,
        int second,
        SparseCollisionDeltaAccumulator deltas,
        List<CollisionPair> contactPairs) {
        // Detection phase: build the delta only when the balls actually overlap.
        CollisionContribution contribution = computeCollisionContribution(balls, first, second);
        if (contribution == null) {
            return;
        }
        deltas.add(contribution);
        contactPairs.add(new CollisionPair(first, second));
    }

    private CollisionContribution computeCollisionContribution(List<Ball> balls, int firstIndex, int secondIndex) {
        var a = balls.get(firstIndex);
        var b = balls.get(secondIndex);

        // Measure the center distance and ignore pairs that are already far enough apart.
        double dx = b.getPos().x() - a.getPos().x();
        double dy = b.getPos().y() - a.getPos().y();
        double dist = Math.hypot(dx, dy);
        double minD = a.getRadius() + b.getRadius();
        if (dist >= minD) {
            return null;
        }

        // Avoid a degenerate normal when the two centers almost coincide.
        if (dist <= PhysicsDefaults.COINCIDENT_CENTER_EPSILON) {
            dx = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
            dy = 0.0;
            dist = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
        }

        // Convert the overlap into a normal vector and a mass-weighted separation.
        double nx = dx / dist;
        double ny = dy / dist;
        double totalMass = a.getMass() + b.getMass();
        double overlap = minD - dist;
        double firstPositionCorrection = overlap * (b.getMass() / totalMass);
        double secondPositionCorrection = overlap * (a.getMass() / totalMass);

        // Compute bounce impulses only when the balls are moving toward each other.
        double firstVelocityDeltaX = 0.0;
        double firstVelocityDeltaY = 0.0;
        double secondVelocityDeltaX = 0.0;
        double secondVelocityDeltaY = 0.0;
        double relativeVelocityX = b.getVel().x() - a.getVel().x();
        double relativeVelocityY = b.getVel().y() - a.getVel().y();
        double relativeVelocityAlongNormal = relativeVelocityX * nx + relativeVelocityY * ny;
        if (relativeVelocityAlongNormal <= 0.0) {
            double impulse = -(1 + PhysicsDefaults.RESTITUTION_FACTOR) * relativeVelocityAlongNormal
                    / (1.0 / a.getMass() + 1.0 / b.getMass());
            firstVelocityDeltaX = -(impulse / a.getMass()) * nx;
            firstVelocityDeltaY = -(impulse / a.getMass()) * ny;
            secondVelocityDeltaX = (impulse / b.getMass()) * nx;
            secondVelocityDeltaY = (impulse / b.getMass()) * ny;
        }

        // Package the delta so it can be merged with the other worker results.
        return new CollisionContribution(
                firstIndex,
                secondIndex,
                -nx * firstPositionCorrection,
                -ny * firstPositionCorrection,
                firstVelocityDeltaX,
                firstVelocityDeltaY,
                nx * secondPositionCorrection,
                ny * secondPositionCorrection,
                secondVelocityDeltaX,
                secondVelocityDeltaY);
    }

    private double computeOwnershipCellSize(List<Ball> balls) {
        // Find the largest ball so the cell size is big enough for every object.
        double maxRadius = Double.NEGATIVE_INFINITY;
        for (var ball : balls) {
            maxRadius = Math.max(maxRadius, ball.getRadius());
        }
        if (maxRadius == Double.NEGATIVE_INFINITY) {
            maxRadius = PhysicsDefaults.MIN_SPATIAL_CELL_SIZE;
        }
        return Math.max(maxRadius * PhysicsDefaults.RADIUS_TO_DIAMETER, PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
    }

    private GridCell gridCellForBallCenter(Ball ball, double cellSize) {
        // Assign the ball to the grid cell that contains its center point.
        return new GridCell(
                SpatialGridSupport.toCellCoordinate(ball.getPos().x(), cellSize),
                SpatialGridSupport.toCellCoordinate(ball.getPos().y(), cellSize));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("executor physics engine is closed");
        }
    }

    private static int defaultPoolSize() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }
    private record CollisionPair(int firstIndex, int secondIndex) {
    }

    private void awaitAll(ArrayList<Future<?>> futures) {
        try {
            for (var future : futures) {
                // Wait for this chunk to finish before moving on to the next one.
                future.get();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("task-based physics step interrupted", ex);
        } catch (ExecutionException ex) {
            var cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("task-based physics step failed", cause);
        }
    }
    private record CellBucket(GridCell cell, IntBag indexes) {
    }

    private record CollisionContribution(
            int firstIndex,
            int secondIndex,
            double firstPositionDeltaX,
            double firstPositionDeltaY,
            double firstVelocityDeltaX,
            double firstVelocityDeltaY,
            double secondPositionDeltaX,
            double secondPositionDeltaY,
            double secondVelocityDeltaX,
            double secondVelocityDeltaY) {
    }

    private static final class IntBag {

        private int[] values = new int[8];
        private int size;

        private void add(int value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        private void addAll(IntBag other) {
            ensureCapacity(size + other.size);
            System.arraycopy(other.values, 0, values, size, other.size);
            size += other.size;
        }

        private int get(int index) {
            return values[index];
        }

        private int size() {
            return size;
        }

        private void ensureCapacity(int required) {
            if (required <= values.length) {
                return;
            }
            int newCapacity = values.length;
            while (newCapacity < required) {
                newCapacity *= 2;
            }
            values = Arrays.copyOf(values, newCapacity);
        }
    }

    private static final class SparseCollisionDeltaAccumulator {

        // Store the summed delta for each ball index.
        private final double[] positionDeltaX;
        private final double[] positionDeltaY;
        private final double[] velocityDeltaX;
        private final double[] velocityDeltaY;
        private final boolean[] touched;
        private int[] touchedIndexes;
        private int touchedCount;

        private SparseCollisionDeltaAccumulator(int ballCount) {
            positionDeltaX = new double[ballCount];
            positionDeltaY = new double[ballCount];
            velocityDeltaX = new double[ballCount];
            velocityDeltaY = new double[ballCount];
            touched = new boolean[ballCount];
            touchedIndexes = new int[Math.max(8, Math.min(ballCount, 64))];
        }

        private void add(CollisionContribution contribution) {
            // Resolution phase: add this collision contribution to the running totals for both balls.
            int first = contribution.firstIndex();
            int second = contribution.secondIndex();
            touch(first);
            touch(second);
            positionDeltaX[first] += contribution.firstPositionDeltaX();
            positionDeltaY[first] += contribution.firstPositionDeltaY();
            velocityDeltaX[first] += contribution.firstVelocityDeltaX();
            velocityDeltaY[first] += contribution.firstVelocityDeltaY();
            positionDeltaX[second] += contribution.secondPositionDeltaX();
            positionDeltaY[second] += contribution.secondPositionDeltaY();
            velocityDeltaX[second] += contribution.secondVelocityDeltaX();
            velocityDeltaY[second] += contribution.secondVelocityDeltaY();
        }

        private void merge(SparseCollisionDeltaAccumulator other) {
            // Resolution phase: merge only the balls that were actually touched by the other worker, preserving the sums.
            for (int i = 0; i < other.touchedCount; i++) {
                int index = other.touchedIndexes[i];
                touch(index);
                positionDeltaX[index] += other.positionDeltaX[index];
                positionDeltaY[index] += other.positionDeltaY[index];
                velocityDeltaX[index] += other.velocityDeltaX[index];
                velocityDeltaY[index] += other.velocityDeltaY[index];
            }
        }

        private int touchedCount() {
            return touchedCount;
        }

        private int touchedIndex(int i) {
            return touchedIndexes[i];
        }

        private double positionDeltaX(int i) {
            return positionDeltaX[i];
        }

        private double positionDeltaY(int i) {
            return positionDeltaY[i];
        }

        private double velocityDeltaX(int i) {
            return velocityDeltaX[i];
        }

        private double velocityDeltaY(int i) {
            return velocityDeltaY[i];
        }

        private void touch(int index) {
            if (touched[index]) {
                return;
            }
            // Resolution phase: record the index once so later merges and application can skip untouched balls.
            touched[index] = true;
            if (touchedCount == touchedIndexes.length) {
                touchedIndexes = Arrays.copyOf(touchedIndexes, touchedIndexes.length * 2);
            }
            touchedIndexes[touchedCount++] = index;
        }
    }

}
