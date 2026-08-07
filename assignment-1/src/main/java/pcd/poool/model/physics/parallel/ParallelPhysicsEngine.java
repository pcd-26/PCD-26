package pcd.poool.model.physics.parallel;

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

/** Shared deterministic parallel physics pipeline. */
public final class ParallelPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_WORKER_COUNT = 1;
    private static final int MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY = 256;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final long maxStepMillis;
    private final RangeScheduler scheduler;
    private final ThreadLocal<ArrayList<Ball>> activeBallsBuffer = ThreadLocal.withInitial(ArrayList::new);
    private boolean closed;

    /** Creates the shared engine with one execution-policy scheduler. */
    public ParallelPhysicsEngine(RangeScheduler scheduler, long maxStepMillis) {
        this.scheduler = java.util.Objects.requireNonNull(scheduler, "scheduler");
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        this.maxStepMillis = maxStepMillis;
    }

    @Override
    public void step(Board board, long elapsedMillis) {
        stepInternal(board, elapsedMillis, false);
    }

    // Runs the same step flow, but also measures the main phases.
    public StepProfile profileStep(Board board, long elapsedMillis) {
        return stepInternal(board, elapsedMillis, true);
    }

    // Returns the number of owned workers.
    public int workerCount() {
        return scheduler.parallelism();
    }

    // Closes the owned workers.
    @Override
    public void close() {
        closed = true;
        scheduler.close();
    }

    private StepProfile stepInternal(Board board, long elapsedMillis, boolean profilingEnabled) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        ensureOpen();
        // Create timing storage only when profiling is requested.
        StepProfileAccumulator profile = profilingEnabled ? new StepProfileAccumulator(scheduler.parallelism()) : null;
        // One writer at a time: workers only operate on private ranges.
        synchronized (board) {
            // Split the elapsed time into fixed sub-steps.
            long remaining = elapsedMillis;
            while (remaining > 0) {
                long dt = Math.min(maxStepMillis, remaining);
                stepOnce(board, dt, profile);
                remaining -= dt;
            }
        }
        return profilingEnabled ? profile.toProfile() : null;
    }

    private void stepOnce(Board board, long dt, StepProfileAccumulator profile) {
        var bounds = board.getBounds();

        long stateReadStart = profile == null ? 0 : System.nanoTime();
        var activeBalls = activeBalls(board);
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - stateReadStart;
        }

        // Movement.
        long integrationStart = profile == null ? 0 : System.nanoTime();
        executeParallelRanges(activeBalls.size(), (from, to, workerIndex) -> {
            long workerStart = profile == null ? 0 : System.nanoTime();
            for (int i = from; i < to; i++) {
                activeBalls.get(i).updateState(dt, bounds);
            }
            if (profile != null) {
                profile.integrationWorkerItems[workerIndex] += to - from;
                profile.integrationWorkerNanos[workerIndex] += System.nanoTime() - workerStart;
            }
        }, profile);
        if (profile != null) {
            long integrationNanos = System.nanoTime() - integrationStart;
            profile.movementNanos += integrationNanos;
        }

        // Pocketing.
        long holeStart = profile == null ? 0 : System.nanoTime();
        board.applyHoleInteractions();
        if (profile != null) {
            profile.holeInteractionNanos += System.nanoTime() - holeStart;
        }

        // Collision detection.
        long collisionStateReadStart = profile == null ? 0 : System.nanoTime();
        var collisionBalls = activeBallsBuffer.get();
        board.fillCandidateCollisionBalls(collisionBalls); // Fills the collision candidates.
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - collisionStateReadStart;
        }
        if (collisionBalls.size() < 2) {
            return;
        }

        // Collision resolution.
        detectAndResolveCollisions(board, collisionBalls, profile);
    }

    private void detectAndResolveCollisions(Board board, List<Ball> balls, StepProfileAccumulator profile) {
        long collisionStart = profile == null ? 0 : System.nanoTime();
        double cellSize = computeOwnershipCellSize(balls);

        // Broad phase: build one private grid per worker.
        @SuppressWarnings("unchecked")
        Map<SpatialGridSupport.GridCell, IntBag>[] workerGrids = new Map[scheduler.parallelism()];
        executeParallelRanges(balls.size(), (from, to, workerIndex) -> {
            long workerStart = profile == null ? 0 : System.nanoTime();
            var localGrid = new HashMap<SpatialGridSupport.GridCell, IntBag>();
            for (int i = from; i < to; i++) {
                CenterCell centerCell = computeCenterCell(balls.get(i), cellSize);
                localGrid.computeIfAbsent(centerCell.cell(), ignored -> new IntBag()).add(i);
            }
            workerGrids[workerIndex] = localGrid;
            if (profile != null) {
                profile.localGridWorkerItems[workerIndex] += to - from;
                profile.localGridWorkerNanos[workerIndex] += System.nanoTime() - workerStart;
            }
        }, profile);

        // Merge the local grids and keep the cells in a stable order.
        long aggregationStart = profile == null ? 0 : System.nanoTime();
        var combinedGrid = new HashMap<SpatialGridSupport.GridCell, IntBag>();
        for (var localGrid : workerGrids) {
            if (localGrid == null) {
                continue;
            }
            for (var entry : localGrid.entrySet()) {
                combinedGrid.computeIfAbsent(entry.getKey(), ignored -> new IntBag()).addAll(entry.getValue());
            }
        }

        var orderedCellBuckets = new ArrayList<CellBucket>(combinedGrid.size());
        int maxCellOccupancy = 0;
        for (var entry : combinedGrid.entrySet()) {
            orderedCellBuckets.add(new CellBucket(entry.getKey(), entry.getValue()));
            maxCellOccupancy = Math.max(maxCellOccupancy, entry.getValue().size());
        }
        orderedCellBuckets.sort((first, second) -> first.cell().compareTo(second.cell()));
        if (profile != null) {
            profile.aggregationNanos += System.nanoTime() - aggregationStart;
            profile.maxCellOccupancy = Math.max(profile.maxCellOccupancy, maxCellOccupancy);
        }

        // Narrow phase: resolve the owned cells in parallel.
        long resolutionStart = profile == null ? 0 : System.nanoTime();
        var workerDeltas = new SparseCollisionDeltaAccumulator[Math.min(scheduler.parallelism(), orderedCellBuckets.size())];
        var workerPairs = new LongBag[Math.min(scheduler.parallelism(), orderedCellBuckets.size())];
        executeParallelRanges(orderedCellBuckets.size(), (from, to, workerIndex) -> {
            var deltaAccumulator = new SparseCollisionDeltaAccumulator(balls.size());
            var pairAccumulator = new LongBag();
            for (int i = from; i < to; i++) {
                resolveOwnedCell(orderedCellBuckets.get(i), combinedGrid, balls, deltaAccumulator, pairAccumulator);
            }
            workerDeltas[workerIndex] = deltaAccumulator;
            workerPairs[workerIndex] = pairAccumulator;
        }, profile);
        if (profile != null) {
            profile.collisionResolutionNanos += System.nanoTime() - resolutionStart;
        }

        // Commit: merge local results, record contacts, then apply the deltas.
        long mergeApplyStart = profile == null ? 0 : System.nanoTime();
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
        applyMergedDeltas(balls, combinedDeltas, profile);
        if (profile != null) {
            profile.collisionDetectionNanos += mergeApplyStart - collisionStart;
            profile.mergeApplyNanos += System.nanoTime() - mergeApplyStart;
            profile.aggregationNanos += System.nanoTime() - mergeApplyStart;
        }
    }

    // Resolves one cell and the neighboring cells it owns.
    private void resolveOwnedCell(
            CellBucket bucket,
            Map<SpatialGridSupport.GridCell, IntBag> mergedGrid,
            List<Ball> balls,
            SparseCollisionDeltaAccumulator deltas,
            LongBag contactPairs) {
        IntBag indexes = bucket.indexes();
        collectPairsWithinBag(indexes, balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x() + 1, bucket.cell().y() - 1)),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x() + 1, bucket.cell().y())),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x() + 1, bucket.cell().y() + 1)),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x(), bucket.cell().y() + 1)),
                balls, deltas, contactPairs);
    }

    // Checks all pairs inside the same cell.
    private void collectPairsWithinBag(
            IntBag indexes,
            List<Ball> balls,
            SparseCollisionDeltaAccumulator deltas,
            LongBag contactPairs) {
        for (int i = 0; i < indexes.size() - 1; i++) {
            int first = indexes.get(i);
            for (int j = i + 1; j < indexes.size(); j++) {
                addContributionIfColliding(balls, first, indexes.get(j), deltas, contactPairs);
            }
        }
    }

    // Checks pairs that span two adjacent cells.
    private void collectCrossPairs(
            IntBag firstBag,
            IntBag secondBag,
            List<Ball> balls,
            SparseCollisionDeltaAccumulator deltas,
            LongBag contactPairs) {
        if (secondBag == null) {
            return;
        }
        for (int i = 0; i < firstBag.size(); i++) {
            int first = firstBag.get(i);
            for (int j = 0; j < secondBag.size(); j++) {
                addContributionIfColliding(balls, first, secondBag.get(j), deltas, contactPairs);
            }
        }
    }

    // Keeps only the pairs that are really colliding.
    private void addContributionIfColliding(
            List<Ball> balls,
            int first,
            int second,
            SparseCollisionDeltaAccumulator deltas,
            LongBag contactPairs) {
        CollisionContribution contribution = computeCollisionContribution(balls, first, second);
        if (contribution == null) {
            return;
        }
        deltas.add(contribution);
        contactPairs.add(encodePair(first, second));
    }

    // Applies the collision deltas to the live balls.
    private void applyMergedDeltas(List<Ball> balls, SparseCollisionDeltaAccumulator merged, StepProfileAccumulator profile) {
        if (merged.touchedCount() == 0) {
            return;
        }
        if (merged.touchedCount() < MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY) {
            // Small touch sets are cheaper to commit serially.
            applyMergedDeltasSequentially(balls, merged);
            return;
        }
        // Each touched ball index goes to exactly one worker.
        executeParallelRanges(merged.touchedCount(), (from, to, workerIndex) -> {
            for (int i = from; i < to; i++) {
                int ballIndex = merged.touchedIndex(i);
                balls.get(ballIndex).translate(new V2d(merged.positionDeltaX(ballIndex), merged.positionDeltaY(ballIndex)));
                balls.get(ballIndex).addVelocity(new V2d(merged.velocityDeltaX(ballIndex), merged.velocityDeltaY(ballIndex)));
            }
        }, profile);
    }

    // Applies the deltas directly when the touched set is small.
    private void applyMergedDeltasSequentially(List<Ball> balls, SparseCollisionDeltaAccumulator merged) {
        for (int i = 0; i < merged.touchedCount(); i++) {
            int ballIndex = merged.touchedIndex(i);
            balls.get(ballIndex).translate(new V2d(merged.positionDeltaX(ballIndex), merged.positionDeltaY(ballIndex)));
            balls.get(ballIndex).addVelocity(new V2d(merged.velocityDeltaX(ballIndex), merged.velocityDeltaY(ballIndex)));
        }
    }

    // Computes the overlap correction and the elastic impulse.
    private CollisionContribution computeCollisionContribution(List<Ball> balls, int firstIndex, int secondIndex) {
        var a = balls.get(firstIndex);
        var b = balls.get(secondIndex);

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
        if (relativeVelocityAlongNormal <= 0.0) {
            double impulse = -(1 + PhysicsDefaults.RESTITUTION_FACTOR) * relativeVelocityAlongNormal
                    / (1.0 / a.getMass() + 1.0 / b.getMass());
            firstVelocityDeltaX = -(impulse / a.getMass()) * nx;
            firstVelocityDeltaY = -(impulse / a.getMass()) * ny;
            secondVelocityDeltaX = (impulse / b.getMass()) * nx;
            secondVelocityDeltaY = (impulse / b.getMass()) * ny;
        }

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

    // Reuses a buffer to collect the active balls.
    private List<Ball> activeBalls(Board board) {
        var activeBalls = activeBallsBuffer.get();
        board.fillCandidateCollisionBalls(activeBalls);
        return activeBalls;
    }

    // Places a ball in the grid cell that contains its center.
    private CenterCell computeCenterCell(Ball ball, double cellSize) {
        return new CenterCell(new SpatialGridSupport.GridCell(
                SpatialGridSupport.toCellCoordinate(ball.getPos().x(), cellSize),
                SpatialGridSupport.toCellCoordinate(ball.getPos().y(), cellSize)));
    }

    // Uses a cell size large enough for the biggest ball.
    private double computeOwnershipCellSize(List<Ball> balls) {
        double maxRadius = Double.NEGATIVE_INFINITY;
        for (var ball : balls) {
            maxRadius = Math.max(maxRadius, ball.getRadius());
        }
        if (maxRadius == Double.NEGATIVE_INFINITY) {
            maxRadius = PhysicsDefaults.MIN_SPATIAL_CELL_SIZE;
        }
        return Math.max(maxRadius * PhysicsDefaults.RADIUS_TO_DIAMETER, PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
    }

    // Executes one chunk per worker and waits for all of them.
    private void executeParallelRanges(int itemCount, RangeTask rangeTask, StepProfileAccumulator profile) {
        if (itemCount == 0) {
            // Nothing to split.
            return;
        }
        var stats = scheduler.execute(itemCount, rangeTask::run);
        if (profile != null) {
            profile.partitionNanos += stats.partitionNanos();
            profile.taskSubmissionNanos += stats.submissionNanos();
            profile.joinOrFutureWaitNanos += stats.waitNanos();
            profile.submittedTasks += stats.submittedTasks();
            profile.lockAcquisitions += stats.coordinationOperations();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("parallel physics engine is closed");
        }
    }

    private static long encodePair(int firstIndex, int secondIndex) {
        int first = Math.min(firstIndex, secondIndex);
        int second = Math.max(firstIndex, secondIndex);
        return (((long) first) << 32) | (second & 0xffffffffL);
    }

    private static int firstIndex(long packedPair) {
        return (int) (packedPair >>> 32);
    }

    private static int secondIndex(long packedPair) {
        return (int) packedPair;
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

    // Per-step timing summary for the threaded pipeline.
    public record StepProfile(
            double syncTimeMillis,
            double aggregationTimeMillis,
            double taskSubmissionTimeMillis,
            double joinOrFutureWaitMillis,
            long lockAcquisitions,
            long submittedTasks,
            double stateReadMillis,
            double partitionMillis,
            double movementMillis,
            double holeInteractionMillis,
            double collisionDetectionMillis,
            double collisionResolutionMillis,
            double mergeApplyMillis) {
    }

    private static final class StepProfileAccumulator {

        private final long[] integrationWorkerNanos;
        private final long[] localGridWorkerNanos;
        private final int[] integrationWorkerItems;
        private final int[] localGridWorkerItems;
        private long stateReadNanos;
        private long partitionNanos;
        private long movementNanos;
        private long holeInteractionNanos;
        private long collisionDetectionNanos;
        private long collisionResolutionNanos;
        private long mergeApplyNanos;
        private long aggregationNanos;
        private long taskSubmissionNanos;
        private long joinOrFutureWaitNanos;
        private long lockAcquisitions;
        private long submittedTasks;
        private int maxCellOccupancy;

        private StepProfileAccumulator(int workerCount) {
            integrationWorkerNanos = new long[workerCount];
            localGridWorkerNanos = new long[workerCount];
            integrationWorkerItems = new int[workerCount];
            localGridWorkerItems = new int[workerCount];
        }

        private StepProfile toProfile() {
            long measuredSyncNanos = taskSubmissionNanos + joinOrFutureWaitNanos;
            return new StepProfile(
                    measuredSyncNanos / NANOS_PER_MILLISECOND,
                    aggregationNanos / NANOS_PER_MILLISECOND,
                    taskSubmissionNanos / NANOS_PER_MILLISECOND,
                    joinOrFutureWaitNanos / NANOS_PER_MILLISECOND,
                    lockAcquisitions,
                    submittedTasks,
                    stateReadNanos / NANOS_PER_MILLISECOND,
                    partitionNanos / NANOS_PER_MILLISECOND,
                    movementNanos / NANOS_PER_MILLISECOND,
                    holeInteractionNanos / NANOS_PER_MILLISECOND,
                    collisionDetectionNanos / NANOS_PER_MILLISECOND,
                    collisionResolutionNanos / NANOS_PER_MILLISECOND,
                    mergeApplyNanos / NANOS_PER_MILLISECOND);
        }
    }
}
