package pcd.poool.model.physics.threaded;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialGridSupport;
import pcd.poool.model.physics.common.SpatialGridSupport.GridCell;

/** Platform-thread physics engine. */
public final class ThreadedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_WORKER_COUNT = 1;
    private static final int MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY = 256;

    private final long maxStepMillis;
    private final PhysicsWorker[] workers;
    private boolean closed;

    public ThreadedPhysicsEngine() {
        this(defaultWorkerCount());
    }

    public ThreadedPhysicsEngine(int workerCount) {
        this(workerCount, PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    public ThreadedPhysicsEngine(int workerCount, long maxStepMillis) {
        if (workerCount < MIN_WORKER_COUNT) {
            throw new IllegalArgumentException("workerCount must be >= 1");
        }
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        // Create one long-lived platform thread per worker slot.
        this.workers = new PhysicsWorker[workerCount];
        for (int i = 0; i < workerCount; i++) {
            workers[i] = new PhysicsWorker("poool-physics-worker-" + i);
        }
        this.maxStepMillis = maxStepMillis;
    }

    @Override
    public void step(Board board, long elapsedMillis) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        ensureOpen();
        // Keep the whole step serialized on the board so the physics state stays consistent.
        synchronized (board) {
            // Split the requested elapsed time into smaller fixed-size physics steps.
            long remaining = elapsedMillis;
            while (remaining > 0) {
                long dt = Math.min(maxStepMillis, remaining);
                stepOnce(board, dt);
                remaining -= dt;
            }
        }
    }

    public int workerCount() {
        return workers.length;
    }

    @Override
    public void close() {
        closed = true;
        // Stop every worker thread before releasing the engine.
        for (var worker : workers) {
            worker.close();
        }
    }

    private void stepOnce(Board board, long dt) {
        // Move the active balls first, keeping the motion phase separate from collision handling.
        advanceActiveBalls(board, dt);

        // Hole interactions can remove balls or change their active state after motion.
        board.applyHoleInteractions();

        // Re-read the active list because pocketing may have changed which balls are still alive.
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

        // Motion phase: update every active ball with its new position and velocity.
        var activeBalls = board.getActiveBalls();
        int activeBallCount = activeBalls.size();
        int activeWorkerCount = Math.min(workers.length, activeBallCount);
        if (activeWorkerCount <= 1) {
            // A single chunk is easier to read and cheaper to run sequentially.
            for (int i = 0; i < activeBallCount; i++) {
                activeBalls.get(i).updateState(dt, bounds);
            }
            return;
        }

        // Split the active balls into contiguous chunks and hand one chunk to each worker.
        int baseSize = activeBallCount / activeWorkerCount;
        int remainder = activeBallCount % activeWorkerCount;
        var completion = new WorkerCompletionMonitor(activeWorkerCount);
        int from = 0;
        for (int workerIndex = 0; workerIndex < activeWorkerCount; workerIndex++) {
            int size = baseSize + (workerIndex < remainder ? 1 : 0);
            int rangeStart = from;
            int rangeEnd = rangeStart + size;
            // Each worker executes the update loop for its own slice.
            workers[workerIndex].assign(() -> {
                for (int i = rangeStart; i < rangeEnd; i++) {
                    activeBalls.get(i).updateState(dt, bounds);
                }
            }, completion);
            from = rangeEnd;
        }
        // The calling thread waits until every worker finishes its slice.
        completion.await();
    }

    private SparseCollisionDeltaAccumulator detectCandidateCollisions(Board board, List<Ball> balls) {
        // Detection phase: use a grid cell size that keeps each ball in a small neighborhood.
        // We only inspect nearby balls, then `computeCollisionContribution(...)` filters out
        // the ones that do not overlap, so this phase finds collision candidates rather than
        // every possible pair.
        double cellSize = computeOwnershipCellSize(balls);
        Map<GridCell, IntBag>[] workerGrids = buildWorkerGrids(balls, cellSize);
        var combinedGrid = mergeWorkerGrids(workerGrids);
        var orderedCellBuckets = orderCellBuckets(combinedGrid);
        return collectCandidateCollisionDeltas(board, balls, combinedGrid, orderedCellBuckets);
    }

    private void resolveCollisions(List<Ball> balls, SparseCollisionDeltaAccumulator accumulatedCollisionDeltas) {
        // Resolution phase: apply all accumulated position and velocity deltas.
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
        // Large batches are reapplied in parallel so the expensive delta phase scales too.
        int touchedCount = accumulatedCollisionDeltas.touchedCount();
        int applyWorkerCount = Math.min(workers.length, touchedCount);
        int baseSize = touchedCount / applyWorkerCount;
        int remainder = touchedCount % applyWorkerCount;
        var completion = new WorkerCompletionMonitor(applyWorkerCount);
        int from = 0;
        for (int workerIndex = 0; workerIndex < applyWorkerCount; workerIndex++) {
            int size = baseSize + (workerIndex < remainder ? 1 : 0);
            int rangeStart = from;
            int rangeEnd = rangeStart + size;
            workers[workerIndex].assign(() -> {
                for (int i = rangeStart; i < rangeEnd; i++) {
                    int ballIndex = accumulatedCollisionDeltas.touchedIndex(i);
                    balls.get(ballIndex).translate(new V2d(accumulatedCollisionDeltas.positionDeltaX(ballIndex), accumulatedCollisionDeltas.positionDeltaY(ballIndex)));
                    balls.get(ballIndex).addVelocity(new V2d(accumulatedCollisionDeltas.velocityDeltaX(ballIndex), accumulatedCollisionDeltas.velocityDeltaY(ballIndex)));
                }
            }, completion);
            from = rangeEnd;
        }
        // Wait until every delta slice has been applied.
        completion.await();
    }

    private Map<GridCell, IntBag>[] buildWorkerGrids(List<Ball> balls, double cellSize) {
        // Build one local grid per worker to avoid write contention.
        @SuppressWarnings("unchecked")
        Map<GridCell, IntBag>[] workerGrids = new Map[workers.length];
        int ballCount = balls.size();
        int gridWorkerCount = Math.min(workers.length, ballCount);
        if (gridWorkerCount <= 1) {
            // Small collections are cheaper to partition directly on the calling thread.
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

        // Each worker builds its own local grid from a contiguous ball slice.
        int baseSize = ballCount / gridWorkerCount;
        int remainder = ballCount % gridWorkerCount;
        var completion = new WorkerCompletionMonitor(gridWorkerCount);
        int from = 0;
        for (int workerIndex = 0; workerIndex < gridWorkerCount; workerIndex++) {
            int size = baseSize + (workerIndex < remainder ? 1 : 0);
            int rangeStart = from;
            int rangeEnd = rangeStart + size;
            int assignedWorker = workerIndex;
            workers[workerIndex].assign(() -> {
                var localGrid = new HashMap<GridCell, IntBag>();
                for (int i = rangeStart; i < rangeEnd; i++) {
                    GridCell assignedCellForBall = gridCellForBallCenter(balls.get(i), cellSize);
                    localGrid.computeIfAbsent(assignedCellForBall, ignored -> new IntBag()).add(i);
                }
                workerGrids[assignedWorker] = localGrid;
            }, completion);
            from = rangeEnd;
        }
        // Wait until every local grid has been built.
        completion.await();
        return workerGrids;
    }

    private HashMap<GridCell, IntBag> mergeWorkerGrids(Map<GridCell, IntBag>[] workerGrids) {
        // Merge worker-local grids into one shared view of the board.
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
        // Turn the merged grid into a stable list so workers process cells in a deterministic order.
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
        var collisionDeltasByWorker = new SparseCollisionDeltaAccumulator[Math.min(workers.length, orderedCellBuckets.size())];
        @SuppressWarnings("unchecked")
        List<CollisionPair>[] collisionPairsByWorker = new List[Math.min(workers.length, orderedCellBuckets.size())];
        int cellCount = orderedCellBuckets.size();
        int cellWorkerCount = Math.min(workers.length, cellCount);
        if (cellWorkerCount <= 1) {
            // A tiny cell set is processed directly to avoid worker overhead.
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
            int baseSize = cellCount / cellWorkerCount;
            int remainder = cellCount % cellWorkerCount;
            var completion = new WorkerCompletionMonitor(cellWorkerCount);
            int from = 0;
            for (int workerIndex = 0; workerIndex < cellWorkerCount; workerIndex++) {
                int size = baseSize + (workerIndex < remainder ? 1 : 0);
                int rangeStart = from;
                int rangeEnd = rangeStart + size;
                int assignedWorker = workerIndex;
                workers[workerIndex].assign(() -> {
                    var deltaAccumulator = new SparseCollisionDeltaAccumulator(balls.size());
                    var pairAccumulator = new ArrayList<CollisionPair>();
                    for (int i = rangeStart; i < rangeEnd; i++) {
                        resolveOwnedCell(orderedCellBuckets.get(i), combinedGrid, balls, deltaAccumulator, pairAccumulator);
                    }
                    collisionDeltasByWorker[assignedWorker] = deltaAccumulator;
                    collisionPairsByWorker[assignedWorker] = pairAccumulator;
                }, completion);
                from = rangeEnd;
            }
            // Wait until all owned cells have been processed.
            completion.await();
        }

        // Merge every worker contribution into a single delta set.
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
        // Detection phase: start with candidate collisions inside the current cell.
        collectPairsWithinBag(indexes, balls, deltas, contactPairs);
        // Detection phase: then check the neighbor cells that belong to this cell's ownership region.
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
        // Detection phase: compare every pair of balls that share the same cell.
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
        // Detection phase: compare each ball in the owned cell with each ball in the adjacent cell.
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
        // Detection phase: compute the collision contribution once and keep it only if the balls overlap.
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

        // Measure the gap between the two centers and stop if they are already separate.
        double dx = b.getPos().x() - a.getPos().x();
        double dy = b.getPos().y() - a.getPos().y();
        double dist = Math.hypot(dx, dy);
        double minD = a.getRadius() + b.getRadius();
        if (dist >= minD) {
            return null;
        }

        // Avoid a zero-length normal when the balls are practically on top of each other.
        if (dist <= PhysicsDefaults.COINCIDENT_CENTER_EPSILON) {
            dx = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
            dy = 0.0;
            dist = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
        }

        // Build the collision normal and split the overlap proportionally to mass.
        double nx = dx / dist;
        double ny = dy / dist;
        double totalMass = a.getMass() + b.getMass();
        double overlap = minD - dist;
        double firstPositionCorrection = overlap * (b.getMass() / totalMass);
        double secondPositionCorrection = overlap * (a.getMass() / totalMass);

        // Prepare velocity deltas only if the balls are actually moving toward each other.
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

        // Return the full delta packet so the caller can apply it later in batch.
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
        // Find the largest ball so the grid cell size can safely contain it.
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
        // Map the ball center to the spatial grid cell that owns it.
        return new GridCell(
                SpatialGridSupport.toCellCoordinate(ball.getPos().x(), cellSize),
                SpatialGridSupport.toCellCoordinate(ball.getPos().y(), cellSize));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("platform-thread physics engine is closed");
        }
    }

    private static int defaultWorkerCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }
    private record CollisionPair(int firstIndex, int secondIndex) {
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
