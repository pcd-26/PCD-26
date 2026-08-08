package pcd.poool.model.physics.taskbased;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepProfile;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialGridSupport;
import pcd.poool.model.physics.parallel.RangeScheduler;

/** Executor Framework physics engine. */
public class TaskBasedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_WORKER_COUNT = 1;
    private static final int MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY = 256;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final long maxStepMillis;
    private final RangeScheduler scheduler;
    private final ThreadLocal<ArrayList<Ball>> activeBallsBuffer = ThreadLocal.withInitial(ArrayList::new);
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
        this.scheduler = new ExecutorRangeScheduler(poolSize);
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        this.maxStepMillis = maxStepMillis;
    }

    @Override
    public void step(Board board, long elapsedMillis) {
        stepInternal(board, elapsedMillis, false);
    }

    // Runs one complete step and, if requested, also collects profiling data.
    public PhysicsStepProfile profileStep(Board board, long elapsedMillis) {
        return stepInternal(board, elapsedMillis, true);
    }

    public int poolSize() {
        return scheduler.parallelism();
    }

    @Override
    public void close() {
        closed = true;
        scheduler.close();
    }

    private PhysicsStepProfile stepInternal(Board board, long elapsedMillis, boolean profilingEnabled) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        ensureOpen();
        // The profile is created only when needed, so the normal step stays lightweight.
        StepProfileAccumulator profile = profilingEnabled ? new StepProfileAccumulator(scheduler.parallelism()) : null;
        synchronized (board) {
        // Split real elapsed time into smaller internal steps and process them sequentially.
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

        // Read the candidate balls first: only these need integration and collision checks.
        long stateReadStart = profile == null ? 0 : System.nanoTime();
        var candidateCollisionBalls = loadCandidateCollisionBalls(board);
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - stateReadStart;
        }

        // Phase 1: movement. Each worker updates one contiguous slice of the list.
        long integrationStart = profile == null ? 0 : System.nanoTime();
        executeParallelRanges(candidateCollisionBalls.size(), (from, to, workerIndex) -> {
            long workerStart = profile == null ? 0 : System.nanoTime();
            for (int i = from; i < to; i++) {
                candidateCollisionBalls.get(i).updateState(dt, bounds);
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

        // Phase 2: hole interactions.
        long holeStart = profile == null ? 0 : System.nanoTime();
        board.applyHoleInteractions();
        if (profile != null) {
            profile.holeInteractionNanos += System.nanoTime() - holeStart;
        }

        // Read the balls again before the broad phase, because some may have been removed.
        long collisionStateReadStart = profile == null ? 0 : System.nanoTime();
        var candidateCollisionBallsForCollisionPhase = activeBallsBuffer.get();
        board.fillCandidateCollisionBalls(candidateCollisionBallsForCollisionPhase);
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - collisionStateReadStart;
        }
        if (candidateCollisionBallsForCollisionPhase.size() < 2) {
            return;
        }

        detectAndResolveCollisions(board, candidateCollisionBallsForCollisionPhase, profile);
    }

    private void detectAndResolveCollisions(Board board, List<Ball> balls, StepProfileAccumulator profile) {
        long collisionStart = profile == null ? 0 : System.nanoTime();
        double cellSize = computeOwnershipCellSize(balls);

        // Broad phase: build one private grid per worker using only occupied cells.
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

        // Merge the local grids into one combined grid, then order the cells.
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

        // Assign each cell to a single worker so the narrow phase stays deterministic.
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

        // Collect the deltas and record the contacts before applying the movements.
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

    // Resolves one cell and only the neighboring cells it owns.
    private void resolveOwnedCell(
            CellBucket bucket,
            Map<SpatialGridSupport.GridCell, IntBag> mergedGrid,
            List<Ball> balls,
            SparseCollisionDeltaAccumulator deltas,
            LongBag contactPairs) {
        IntBag indexes = bucket.indexes();
        // Collisions inside the current cell.
        collectPairsWithinBag(indexes, balls, deltas, contactPairs);
        // Collisions with the neighboring cells owned by this cell.
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x() + 1, bucket.cell().y() - 1)),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x() + 1, bucket.cell().y())),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x() + 1, bucket.cell().y() + 1)),
                balls, deltas, contactPairs);
        collectCrossPairs(indexes, mergedGrid.get(new SpatialGridSupport.GridCell(bucket.cell().x(), bucket.cell().y() + 1)),
                balls, deltas, contactPairs);
    }

    // Checks every pair inside the same cell.
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

    // If two balls are really colliding, store the effect to apply later.
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

    // Apply all collected deltas: in parallel when it pays off, otherwise sequentially.
    private void applyMergedDeltas(List<Ball> balls, SparseCollisionDeltaAccumulator merged, StepProfileAccumulator profile) {
        if (merged.touchedCount() == 0) {
            return;
        }
        if (merged.touchedCount() < MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY) {
            // With few touched balls, the parallel overhead is not worth it.
            applyMergedDeltasSequentially(balls, merged);
            return;
        }
        // Each touched index goes into its own distinct range.
        executeParallelRanges(merged.touchedCount(), (from, to, workerIndex) -> {
            for (int i = from; i < to; i++) {
                int ballIndex = merged.touchedIndex(i);
                balls.get(ballIndex).translate(new V2d(merged.positionDeltaX(ballIndex), merged.positionDeltaY(ballIndex)));
                balls.get(ballIndex).addVelocity(new V2d(merged.velocityDeltaX(ballIndex), merged.velocityDeltaY(ballIndex)));
            }
        }, profile);
    }

    // Direct variant when the touched set is small.
    private void applyMergedDeltasSequentially(List<Ball> balls, SparseCollisionDeltaAccumulator merged) {
        for (int i = 0; i < merged.touchedCount(); i++) {
            int ballIndex = merged.touchedIndex(i);
            balls.get(ballIndex).translate(new V2d(merged.positionDeltaX(ballIndex), merged.positionDeltaY(ballIndex)));
            balls.get(ballIndex).addVelocity(new V2d(merged.velocityDeltaX(ballIndex), merged.velocityDeltaY(ballIndex)));
        }
    }

    // Compute the position correction and elastic impulse for one colliding pair.
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

        // If the centers are almost identical, force a stable normal.
        if (dist <= PhysicsDefaults.COINCIDENT_CENTER_EPSILON) {
            dx = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
            dy = 0.0;
            dist = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
        }

        // Collision direction and overlap correction.
        double nx = dx / dist;
        double ny = dy / dist;
        double totalMass = a.getMass() + b.getMass();
        double overlap = minD - dist;
        double firstPositionCorrection = overlap * (b.getMass() / totalMass);
        double secondPositionCorrection = overlap * (a.getMass() / totalMass);

        // If the balls are moving toward each other along the normal, also compute the impulse.
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

    // Reload only the candidate balls because the board may have changed the live list.
    private List<Ball> loadCandidateCollisionBalls(Board board) {
        var balls = activeBallsBuffer.get();
        board.fillCandidateCollisionBalls(balls);
        return balls;
    }

    // The cell size must be large enough to avoid splitting one ball across too many cells.
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

    // Use the cell that contains the ball center as its owner cell.
    private CenterCell computeCenterCell(Ball ball, double cellSize) {
        return new CenterCell(new SpatialGridSupport.GridCell(
                SpatialGridSupport.toCellCoordinate(ball.getPos().x(), cellSize),
                SpatialGridSupport.toCellCoordinate(ball.getPos().y(), cellSize)));
    }

    // Split the work into contiguous ranges and hand the results to the concrete scheduler.
    private void executeParallelRanges(int itemCount, RangeTask rangeTask, StepProfileAccumulator profile) {
        if (itemCount == 0) {
            return;
        }
        var stats = scheduler.execute(itemCount, rangeTask::run);
        if (profile != null) {
            profile.partitionNanos += stats.partitionNanos();
            profile.taskSubmissionNanos += stats.submissionNanos();
            profile.joinOrFutureWaitNanos += stats.waitNanos();
            profile.lockAcquisitions += stats.coordinationOperations();
            profile.submittedTasks += stats.submittedTasks();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("executor physics engine is closed");
        }
    }

    private static long encodePair(int first, int second) {
        // Pack the pair so it stays ordered and deduplicated.
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

    private static int defaultPoolSize() {
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
            // Accumulate position and velocity for both balls involved.
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
            // Merge only the indexes actually touched by the other worker.
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

        private PhysicsStepProfile toProfile() {
            long measuredSyncNanos = taskSubmissionNanos + joinOrFutureWaitNanos;
            return new PhysicsStepProfile(
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
