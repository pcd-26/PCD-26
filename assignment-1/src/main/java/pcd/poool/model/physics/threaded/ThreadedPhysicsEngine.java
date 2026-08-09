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
        double cellSize = computeOwnershipCellSize(balls); // Grid size based on the largest ball.
        Map<GridCell, IntBag>[] workerGrids = buildWorkerGrids(balls, cellSize); // Local grids per worker.
        var combinedGrid = mergeWorkerGrids(workerGrids); // Merge the local grids.
        var orderedCellBuckets = orderCellBuckets(combinedGrid); // Keep cell processing deterministic.
        var candidatePairs = collectCandidateCollisionPairs(balls, combinedGrid, orderedCellBuckets);
        return collectCollisionContributions(board, balls, candidatePairs);
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

    private List<CollisionPair> collectCandidateCollisionPairs(
            List<Ball> balls,
            Map<GridCell, IntBag> combinedGrid,
            ArrayList<CellBucket> orderedCellBuckets) {
        var collisionPairsByWorker = new List[Math.min(workers.length, orderedCellBuckets.size())];
        int cellCount = orderedCellBuckets.size();
        int cellWorkerCount = Math.min(workers.length, cellCount);
        if (cellWorkerCount <= 1) {
            // A tiny cell set is processed directly to avoid worker overhead.
            var pairAccumulator = new ArrayList<CollisionPair>();
            for (int i = 0; i < cellCount; i++) {
                collectCellCandidatePairs(orderedCellBuckets.get(i), combinedGrid, balls, pairAccumulator);
            }
            if (cellCount > 0) {
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
                    var pairAccumulator = new ArrayList<CollisionPair>();
                    for (int i = rangeStart; i < rangeEnd; i++) {
                        collectCellCandidatePairs(orderedCellBuckets.get(i), combinedGrid, balls, pairAccumulator);
                    }
                    collisionPairsByWorker[assignedWorker] = pairAccumulator;
                }, completion);
                from = rangeEnd;
            }
            // Wait until all owned cells have been processed.
            completion.await();
        }

        // Collect all confirmed collision candidates and keep them ordered.
        int pairCount = 0;
        for (var localPairBag : collisionPairsByWorker) {
            if (localPairBag != null) {
                pairCount += localPairBag.size();
            }
        }
        var candidatePairs = new ArrayList<CollisionPair>(pairCount);
        for (var localPairBag : collisionPairsByWorker) {
            if (localPairBag == null) {
                continue;
            }
            candidatePairs.addAll(localPairBag);
        }
        candidatePairs.sort((first, second) -> {
            int byFirst = Integer.compare(first.firstIndex(), second.firstIndex());
            if (byFirst != 0) {
                return byFirst;
            }
            return Integer.compare(first.secondIndex(), second.secondIndex());
        });
        return candidatePairs;
    }

    private SparseCollisionDeltaAccumulator collectCollisionContributions(
            Board board,
            List<Ball> balls,
            List<CollisionPair> candidatePairs) {
        var collisionDeltas = new SparseCollisionDeltaAccumulator(balls.size());
        for (var pair : candidatePairs) {
            var contribution = computeCollisionContribution(balls, pair.firstIndex(), pair.secondIndex());
            if (contribution == null) {
                continue;
            }
            collisionDeltas.add(contribution);
            board.recordCollision(balls.get(pair.firstIndex()), balls.get(pair.secondIndex()));
        }
        return collisionDeltas;
    }

    private void collectCellCandidatePairs(
            CellBucket bucket,
            Map<GridCell, IntBag> mergedGrid,
            List<Ball> balls,
            List<CollisionPair> contactPairs) {
        IntBag indexes = bucket.indexes();
        // Check the cell itself first.
        collectPairsWithinBag(indexes, balls, contactPairs);
        // Then check the neighboring cells that can still collide with it.
        collectCrossPairs(indexes, mergedGrid.get(new GridCell(bucket.cell().x() + 1, bucket.cell().y() - 1)),
                balls, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new GridCell(bucket.cell().x() + 1, bucket.cell().y())),
                balls, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new GridCell(bucket.cell().x() + 1, bucket.cell().y() + 1)),
                balls, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new GridCell(bucket.cell().x(), bucket.cell().y() + 1)),
                balls, contactPairs);
    }

    private void collectPairsWithinBag(
            IntBag indexes,
            List<Ball> balls,
            List<CollisionPair> contactPairs) {
        // Detection phase: compare every pair of balls that share the same cell.
        for (int i = 0; i < indexes.size() - 1; i++) {
            int first = indexes.get(i);
            for (int j = i + 1; j < indexes.size(); j++) {
                addCandidatePairIfColliding(balls, first, indexes.get(j), contactPairs);
            }
        }
    }

    private void collectCrossPairs(
            IntBag firstBag,
            IntBag secondBag,
            List<Ball> balls,
            List<CollisionPair> contactPairs) {
        if (secondBag == null) {
            return;
        }
        // Detection phase: compare each ball in the owned cell with each ball in the adjacent cell.
        for (int i = 0; i < firstBag.size(); i++) {
            int first = firstBag.get(i);
            for (int j = 0; j < secondBag.size(); j++) {
                addCandidatePairIfColliding(balls, first, secondBag.get(j), contactPairs);
            }
        }
    }

    private void addCandidatePairIfColliding(
            List<Ball> balls,
            int first,
            int second,
            List<CollisionPair> contactPairs) {
        // Detection phase: keep only overlapping pairs.
        if (!areOverlapping(balls, first, second)) {
            return;
        }
        contactPairs.add(new CollisionPair(first, second));
    }

    private boolean areOverlapping(List<Ball> balls, int firstIndex, int secondIndex) {
        var firstBall = balls.get(firstIndex);
        var secondBall = balls.get(secondIndex);
        double dx = secondBall.getPos().x() - firstBall.getPos().x();
        double dy = secondBall.getPos().y() - firstBall.getPos().y();
        double dist = Math.hypot(dx, dy);
        return dist < firstBall.getRadius() + secondBall.getRadius();
    }

    private CollisionContribution computeCollisionContribution(List<Ball> balls, int firstIndex, int secondIndex) {
        var firstBall = balls.get(firstIndex);
        var secondBall = balls.get(secondIndex);

        double centerDeltaX = secondBall.getPos().x() - firstBall.getPos().x();
        double centerDeltaY = secondBall.getPos().y() - firstBall.getPos().y();
        double centerDistance = Math.hypot(centerDeltaX, centerDeltaY);
        double minAllowedDistance = firstBall.getRadius() + secondBall.getRadius();
        if (centerDistance >= minAllowedDistance) {
            return null;
        }

        // Avoid a zero-length collision axis.
        if (centerDistance <= PhysicsDefaults.COINCIDENT_CENTER_EPSILON) {
            centerDeltaX = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
            centerDeltaY = 0.0;
            centerDistance = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
        }

        double collisionAxisX = centerDeltaX / centerDistance;
        double collisionAxisY = centerDeltaY / centerDistance;
        double totalMass = firstBall.getMass() + secondBall.getMass();
        double overlapDistance = minAllowedDistance - centerDistance;
        double firstPositionDelta = overlapDistance * (secondBall.getMass() / totalMass);
        double secondPositionDelta = overlapDistance * (firstBall.getMass() / totalMass);

        double firstVelocityDeltaX = 0.0;
        double firstVelocityDeltaY = 0.0;
        double secondVelocityDeltaX = 0.0;
        double secondVelocityDeltaY = 0.0;
        double relativeVelocityX = secondBall.getVel().x() - firstBall.getVel().x();
        double relativeVelocityY = secondBall.getVel().y() - firstBall.getVel().y();
        double relativeVelocityOnAxis = relativeVelocityX * collisionAxisX + relativeVelocityY * collisionAxisY;
        if (relativeVelocityOnAxis <= 0.0) {
            // Apply an elastic impulse only if the balls are moving toward each other.
            double bounceImpulse = -(1 + PhysicsDefaults.RESTITUTION_FACTOR) * relativeVelocityOnAxis
                    / (1.0 / firstBall.getMass() + 1.0 / secondBall.getMass());
            firstVelocityDeltaX = -(bounceImpulse / firstBall.getMass()) * collisionAxisX;
            firstVelocityDeltaY = -(bounceImpulse / firstBall.getMass()) * collisionAxisY;
            secondVelocityDeltaX = (bounceImpulse / secondBall.getMass()) * collisionAxisX;
            secondVelocityDeltaY = (bounceImpulse / secondBall.getMass()) * collisionAxisY;
        }

        // Move the first ball backward and the second ball forward on the collision axis.
        return new CollisionContribution(
                firstIndex,					                // first ball index
                secondIndex,					            // second ball index
                -collisionAxisX * firstPositionDelta,	    // first ball position delta x
                -collisionAxisY * firstPositionDelta,	    // first ball position delta y
                firstVelocityDeltaX,			            // first ball velocity delta x
                firstVelocityDeltaY,			            // first ball velocity delta y
                collisionAxisX * secondPositionDelta,	    // second ball position delta x
                collisionAxisY * secondPositionDelta,	    // second ball position delta y
                secondVelocityDeltaX,			            // second ball velocity delta x
                secondVelocityDeltaY);			            // second ball velocity delta y
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
