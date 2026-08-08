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
        stepInternal(board, elapsedMillis);
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

    private void stepInternal(Board board, long elapsedMillis) {
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

    private void stepOnce(Board board, long dt) {
        var bounds = board.getBounds();

        // First update every active ball with its new position and velocity.
        var activeBalls = board.getActiveBalls();

        executeParallelRanges(activeBalls.size(), (from, to, workerIndex) -> {
            // Each worker owns a disjoint slice of the active ball list.
            for (int i = from; i < to; i++) {
                activeBalls.get(i).updateState(dt, bounds);
            }
        });

        // Hole interactions can remove balls or change their active state after motion.
        board.applyHoleInteractions();

        // Re-read the active list because pocketing may have changed which balls are still alive.
        var activeBallsForCollisionPhase = board.getActiveBalls();
        if (activeBallsForCollisionPhase.size() < 2) {
            return;
        }

        // Resolve collisions only if at least two balls are still active.
        detectAndResolveCollisions(board, activeBallsForCollisionPhase);
    }

    private void detectAndResolveCollisions(Board board, List<Ball> balls) {
        // Estimate a grid cell size that keeps each ball in a manageable neighborhood.
        double cellSize = computeOwnershipCellSize(balls);

        // Build a spatial hash in parallel, one local grid per worker.
        @SuppressWarnings("unchecked")
        Map<SpatialGridSupport.GridCell, IntBag>[] workerGrids = new Map[workers.length];
        executeParallelRanges(balls.size(), (from, to, workerIndex) -> {
            var localGrid = new HashMap<SpatialGridSupport.GridCell, IntBag>();
            // Each ball is assigned to the cell containing its center.
            for (int i = from; i < to; i++) {
                CenterCell centerCell = computeCenterCell(balls.get(i), cellSize);
                localGrid.computeIfAbsent(centerCell.cell(), ignored -> new IntBag()).add(i);
            }
            workerGrids[workerIndex] = localGrid;
        });

        // Merge worker-local grids into one shared view of the board.
        var combinedGrid = new HashMap<SpatialGridSupport.GridCell, IntBag>();
        for (var localGrid : workerGrids) {
            if (localGrid == null) {
                continue;
            }
            for (var entry : localGrid.entrySet()) {
                combinedGrid.computeIfAbsent(entry.getKey(), ignored -> new IntBag()).addAll(entry.getValue());
            }
        }

        // Turn the merged grid into a stable list so workers process cells in a deterministic order.
        var orderedCellBuckets = new ArrayList<CellBucket>(combinedGrid.size());
        for (var entry : combinedGrid.entrySet()) {
            orderedCellBuckets.add(new CellBucket(entry.getKey(), entry.getValue()));
        }
        orderedCellBuckets.sort((first, second) -> first.cell().compareTo(second.cell()));

        // Each worker accumulates the corrections and contact pairs for the cells it owns.
        var workerDeltas = new SparseCollisionDeltaAccumulator[Math.min(workers.length, orderedCellBuckets.size())];
        var workerPairs = new LongBag[Math.min(workers.length, orderedCellBuckets.size())];
        executeParallelRanges(orderedCellBuckets.size(), (from, to, workerIndex) -> {
            var deltaAccumulator = new SparseCollisionDeltaAccumulator(balls.size());
            var pairAccumulator = new LongBag();
            for (int i = from; i < to; i++) {
                resolveOwnedCell(orderedCellBuckets.get(i), combinedGrid, balls, deltaAccumulator, pairAccumulator);
            }
            workerDeltas[workerIndex] = deltaAccumulator;
            workerPairs[workerIndex] = pairAccumulator;
        });

        // Merge every worker contribution into a single correction set.
        var combinedDeltas = new SparseCollisionDeltaAccumulator(balls.size());
        int pairCount = 0;
        for (int i = 0; i < workerDeltas.length; i++) {
            if (workerDeltas[i] != null) {
                combinedDeltas.merge(workerDeltas[i]);
            }
            if (workerPairs[i] != null) {
                pairCount += workerPairs[i].size();
            }
        }

        // Collect all contact pairs, sort them, and report each collision once.
        long[] contactPairs = new long[pairCount];
        int offset = 0;
        for (var localPairBag : workerPairs) {
            if (localPairBag == null) {
                continue;
            }
            offset = localPairBag.copyInto(contactPairs, offset);
        }
        Arrays.sort(contactPairs);
        for (long packedPair : contactPairs) {
            board.recordCollision(balls.get(firstIndex(packedPair)), balls.get(secondIndex(packedPair)));
        }

        // Apply all position and velocity corrections after collision detection finishes.
        applyMergedDeltas(balls, combinedDeltas);
    }

    private void resolveOwnedCell(
            CellBucket bucket,
            Map<SpatialGridSupport.GridCell, IntBag> mergedGrid,
            List<Ball> balls,
            SparseCollisionDeltaAccumulator deltas,
            LongBag contactPairs) {
        IntBag indexes = bucket.indexes();
        // Start with pairs inside the current cell.
        collectPairsWithinBag(indexes, balls, deltas, contactPairs);
        // Then check the neighbor cells that belong to this cell's ownership region.
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x() + 1, bucket.cell().y() - 1)),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x() + 1, bucket.cell().y())),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x() + 1, bucket.cell().y() + 1)),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x(), bucket.cell().y() + 1)),
                balls, deltas, contactPairs);
    }

    private void collectPairsWithinBag(
            IntBag indexes,
            List<Ball> balls,
            SparseCollisionDeltaAccumulator deltas,
            LongBag contactPairs) {
        // Compare every pair of balls that share the same cell.
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
            LongBag contactPairs) {
        if (secondBag == null) {
            return;
        }
        // Compare each ball in the owned cell with each ball in the adjacent cell.
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
            LongBag contactPairs) {
        // Compute the physical correction once and keep it only if the balls overlap.
        CollisionContribution contribution = computeCollisionContribution(balls, first, second);
        if (contribution == null) {
            return;
        }
        deltas.add(contribution);
        contactPairs.add(encodePair(first, second));
    }

    private void applyMergedDeltas(List<Ball> balls, SparseCollisionDeltaAccumulator merged) {
        // Skip the apply phase entirely if no ball was touched by any collision.
        if (merged.touchedCount() == 0) {
            return;
        }
        // Small batches are cheaper to apply sequentially than to split again.
        if (merged.touchedCount() < MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY) {
            applyMergedDeltasSequentially(balls, merged);
            return;
        }
        // Large batches are reapplied in parallel so the expensive correction phase scales too.
        executeParallelRanges(merged.touchedCount(), (from, to, workerIndex) -> {
            for (int i = from; i < to; i++) {
                int ballIndex = merged.touchedIndex(i);
                balls.get(ballIndex).translate(new V2d(merged.positionDeltaX(ballIndex), merged.positionDeltaY(ballIndex)));
                balls.get(ballIndex).addVelocity(new V2d(merged.velocityDeltaX(ballIndex), merged.velocityDeltaY(ballIndex)));
            }
        });
    }

    private void applyMergedDeltasSequentially(List<Ball> balls, SparseCollisionDeltaAccumulator merged) {
        // Apply every stored correction in a simple deterministic loop.
        for (int i = 0; i < merged.touchedCount(); i++) {
            int ballIndex = merged.touchedIndex(i);
            balls.get(ballIndex).translate(new V2d(merged.positionDeltaX(ballIndex), merged.positionDeltaY(ballIndex)));
            balls.get(ballIndex).addVelocity(new V2d(merged.velocityDeltaX(ballIndex), merged.velocityDeltaY(ballIndex)));
        }
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

        // Prepare velocity corrections only if the balls are actually moving toward each other.
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

        // Return the full correction packet so the caller can apply it later in batch.
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

    private CenterCell computeCenterCell(Ball ball, double cellSize) {
        // Map the ball center to the spatial grid cell that owns it.
        return new CenterCell(new SpatialGridSupport.GridCell(
                SpatialGridSupport.toCellCoordinate(ball.getPos().x(), cellSize),
                SpatialGridSupport.toCellCoordinate(ball.getPos().y(), cellSize)));
    }

    private void executeParallelRanges(int itemCount, RangeTask rangeTask) {
        if (itemCount == 0) {
            return;
        }
        // Split the work into contiguous chunks so each worker gets a stable slice.
        int usedWorkers = Math.min(workers.length, itemCount);
        if (usedWorkers <= 1) {
            rangeTask.run(0, itemCount, 0);
            return;
        }
        int baseSize = itemCount / usedWorkers;
        int remainder = itemCount % usedWorkers;
        var completion = new WorkerCompletionMonitor(usedWorkers);
        int from = 0;
        for (int workerIndex = 0; workerIndex < usedWorkers; workerIndex++) {
            int size = baseSize + (workerIndex < remainder ? 1 : 0);
            int rangeStart = from;
            int rangeEnd = rangeStart + size;
            int assignedWorker = workerIndex;
            // Hand the chunk to the worker and let the monitor track completion.
            workers[workerIndex].assign(
                    () -> rangeTask.run(rangeStart, rangeEnd, assignedWorker),
                    completion);
            from = rangeEnd;
        }
        // Block the calling thread until every worker finishes its chunk.
        completion.await();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("platform-thread physics engine is closed");
        }
    }

    private static long encodePair(int first, int second) {
        // Pack the pair so the two indexes can be sorted and deduplicated cheaply.
        int low = Math.min(first, second);
        int high = Math.max(first, second);
        return (((long) low) << 32) | (high & 0xffffffffL);
    }

    private static int firstIndex(long packedPair) {
        return (int) (packedPair >> 32);
    }

    private static int secondIndex(long packedPair) {
        return (int) packedPair;
    }

    private static int defaultWorkerCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    @FunctionalInterface
    private interface RangeTask {
        void run(int fromInclusive, int toExclusive, int workerIndex);
    }

    private record CenterCell(SpatialGridSupport.GridCell cell) {
    }

    private record CellBucket(SpatialGridSupport.GridCell cell, IntBag indexes) {
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

    private static final class LongBag {

        private long[] values = new long[16];
        private int size;

        private void add(long value) {
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        private int size() {
            return size;
        }

        private int copyInto(long[] target, int offset) {
            System.arraycopy(values, 0, target, offset, size);
            return offset + size;
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
            // Accumulate the positional and velocity correction for both balls.
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
            // Merge only the balls that were actually touched by the other worker.
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
            touched[index] = true;
            if (touchedCount == touchedIndexes.length) {
                touchedIndexes = Arrays.copyOf(touchedIndexes, touchedIndexes.length * 2);
            }
            touchedIndexes[touchedCount++] = index;
        }
    }

}
