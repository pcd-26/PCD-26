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

// Platform-threaded physics engine.
public class ThreadedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_WORKER_COUNT = 1;
    private static final int MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY = 256;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final long maxStepMillis;
    private final PhysicsWorker[] workers;
    private final ThreadLocal<ArrayList<Ball>> activeBallsBuffer = ThreadLocal.withInitial(ArrayList::new);
    private boolean closed;

    // Uses the default worker count.
    public ThreadedPhysicsEngine() {
        this(defaultWorkerCount());
    }

    // Creates a threaded engine with a custom worker count.
    public ThreadedPhysicsEngine(int workerCount) {
        this(workerCount, PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    // Creates a threaded engine with custom worker count and sub-step duration.
    public ThreadedPhysicsEngine(int workerCount, long maxStepMillis) {
        if (workerCount < MIN_WORKER_COUNT) {
            throw new IllegalArgumentException("workerCount must be >= 1");
        }
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        this.maxStepMillis = maxStepMillis;
        this.workers = new PhysicsWorker[workerCount];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new PhysicsWorker("poool-physics-worker-" + i);
        }
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
        return workers.length;
    }

    // Closes the owned workers.
    @Override
    public void close() {
        closed = true;
        for (var worker : workers) {
            worker.close();
        }
    }

    private StepProfile stepInternal(Board board, long elapsedMillis, boolean profilingEnabled) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        ensureOpen();
        // Create timing storage only when profiling is requested.
        StepProfileAccumulator profile = profilingEnabled ? new StepProfileAccumulator(workers.length) : null;
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
        // Read the shared bounds once for this sub-step.
        var bounds = board.getBounds();
        // Read the active balls once for this sub-step.
        long stateReadStart = profile == null ? 0 : System.nanoTime();
        var activeBalls = activeBalls(board);
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - stateReadStart;
        }

        // Integrate motion in parallel, one slice per worker.
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
        }, profile);
        if (profile != null) {
            long integrationNanos = System.nanoTime() - integrationStart;
            profile.movementNanos += integrationNanos;
        }

        // Apply pocketing after movement.
        long holeStart = profile == null ? 0 : System.nanoTime();
        board.applyHoleInteractions();
        if (profile != null) {
            profile.holeInteractionNanos += System.nanoTime() - holeStart;
        }

        // Rebuild the collision list from the updated board state.
        long collisionStateReadStart = profile == null ? 0 : System.nanoTime();
        var collisionBalls = activeBallsBuffer.get();
        board.fillCollisionBalls(collisionBalls);
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - collisionStateReadStart;
        }
        if (collisionBalls.size() < 2) {
            return;
        }
        // Resolve the collisions from the updated positions.
        detectAndResolveCollisions(board, collisionBalls, profile);
    }

    private void detectAndResolveCollisions(Board board, List<Ball> balls, StepProfileAccumulator profile) {
        long collisionStart = profile == null ? 0 : System.nanoTime();
        // Build a spatial partition so nearby balls are compared together.
        double cellSize = computeOwnershipCellSize(balls);
        CenterCell[] centerCells = new CenterCell[balls.size()];

        @SuppressWarnings("unchecked")
        Map<SpatialGridSupport.GridCell, IntBag>[] localGrids = new Map[workers.length];
        // Each worker builds a private grid, then the coordinator merges it.
        runRanges(balls.size(), (from, to, workerIndex) -> {
            long workerStart = profile == null ? 0 : System.nanoTime();
            var localGrid = new HashMap<SpatialGridSupport.GridCell, IntBag>();
            for (int i = from; i < to; i++) {
                CenterCell centerCell = computeCenterCell(balls.get(i), cellSize);
                centerCells[i] = centerCell;
                localGrid.computeIfAbsent(centerCell.cell(), ignored -> new IntBag()).add(i);
            }
            localGrids[workerIndex] = localGrid;
            if (profile != null) {
                profile.localGridWorkerItems[workerIndex] += to - from;
                profile.localGridWorkerNanos[workerIndex] += System.nanoTime() - workerStart;
            }
        }, profile);
        long aggregationStart = profile == null ? 0 : System.nanoTime();
        // Merge first, sort later, keep collision ownership deterministic.
        var mergedGrid = new HashMap<SpatialGridSupport.GridCell, IntBag>();
        for (var localGrid : localGrids) {
            if (localGrid == null) {
                continue;
            }
            for (var entry : localGrid.entrySet()) {
                mergedGrid.computeIfAbsent(entry.getKey(), ignored -> new IntBag()).addAll(entry.getValue());
            }
        }

        var orderedCells = new ArrayList<CellBucket>(mergedGrid.size());
        int maxCellOccupancy = 0;
        for (var entry : mergedGrid.entrySet()) {
            orderedCells.add(new CellBucket(entry.getKey(), entry.getValue()));
            maxCellOccupancy = Math.max(maxCellOccupancy, entry.getValue().size());
        }
        orderedCells.sort((first, second) -> first.cell().compareTo(second.cell()));
        if (profile != null) {
            profile.aggregationNanos += System.nanoTime() - aggregationStart;
            profile.maxCellOccupancy = Math.max(profile.maxCellOccupancy, maxCellOccupancy);
        }

        long resolutionStart = profile == null ? 0 : System.nanoTime();
        var localDeltas = new SparseCollisionDeltaAccumulator[Math.min(workers.length, orderedCells.size())];
        var localPairs = new LongBag[Math.min(workers.length, orderedCells.size())];
        // Workers compute deltas, but the board is still untouched here.
        runRanges(orderedCells.size(), (from, to, workerIndex) -> {
            var deltaAccumulator = new SparseCollisionDeltaAccumulator(balls.size());
            var pairAccumulator = new LongBag();
            for (int i = from; i < to; i++) {
                resolveOwnedCell(orderedCells.get(i), mergedGrid, balls, deltaAccumulator, pairAccumulator);
            }
            localDeltas[workerIndex] = deltaAccumulator;
            localPairs[workerIndex] = pairAccumulator;
        }, profile);
        if (profile != null) {
            profile.collisionResolutionNanos += System.nanoTime() - resolutionStart;
        }

        long mergeApplyStart = profile == null ? 0 : System.nanoTime();
        // Combine all worker results and apply them to the live balls.
        var mergedDeltas = new SparseCollisionDeltaAccumulator(balls.size());
        int pairCount = 0;
        for (int i = 0; i < localDeltas.length; i++) {
            if (localDeltas[i] != null) {
                mergedDeltas.merge(localDeltas[i]);
            }
            if (localPairs[i] != null) {
                pairCount += localPairs[i].size();
            }
        }

        long[] contactPairs = new long[pairCount];
        int offset = 0;
        for (var localPairBag : localPairs) {
            if (localPairBag == null) {
                continue;
            }
            offset = localPairBag.copyInto(contactPairs, offset);
        }
        Arrays.sort(contactPairs);
        for (long packedPair : contactPairs) {
            board.recordCollision(balls.get(firstIndex(packedPair)), balls.get(secondIndex(packedPair)));
        }
        applyMergedDeltas(balls, mergedDeltas, profile);
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
        runRanges(merged.touchedCount(), (from, to, workerIndex) -> {
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
        board.fillCollisionBalls(activeBalls);
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

    // Splits the work across workers and waits for all of them.
    private void runRanges(int itemCount, RangeTask rangeTask, StepProfileAccumulator profile) {
        if (itemCount == 0) {
            // Nothing to split.
            return;
        }
        // Use only as many workers as the current work can fill.
        int workerCount = Math.min(workers.length, itemCount);
        if (workerCount == 1) {
            // Run directly when there is no real split to make.
            long partitionStart = profile == null ? 0 : System.nanoTime();
            if (profile != null) {
                profile.partitionNanos += System.nanoTime() - partitionStart;
            }
            rangeTask.run(0, itemCount, 0);
            return;
        }
        // Track when every worker has finished its range.
        var completion = new WorkerCompletionMonitor(workerCount);
        // Compute contiguous chunks of roughly equal size.
        long partitionStart = profile == null ? 0 : System.nanoTime();
        int itemsPerWorker = itemCount / workerCount;
        int extraItems = itemCount % workerCount;
        int rangeStart = 0;
        if (profile != null) {
            profile.partitionNanos += System.nanoTime() - partitionStart;
        }
        // Hand one chunk to each worker.
        long submissionStart = profile == null ? 0 : System.nanoTime();
        for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
            // Spread the extra items over the first workers.
            int chunkSize = itemsPerWorker + (workerIndex < extraItems ? 1 : 0);
            // Each worker gets a contiguous slice of the input.
            int start = rangeStart;
            int rangeEnd = start + chunkSize;
            int assignedWorker = workerIndex;
            workers[workerIndex].assign(() -> rangeTask.run(start, rangeEnd, assignedWorker), completion);
            rangeStart = rangeEnd;
        }
        if (profile != null) {
            profile.taskSubmissionNanos += System.nanoTime() - submissionStart;
            profile.submittedTasks += workerCount;
            profile.lockAcquisitions += workerCount + 1L;
        }
        // Wait for all workers before returning.
        long waitStart = profile == null ? 0 : System.nanoTime();
        // Phase barrier: merge only after every worker finishes.
        completion.await();
        if (profile != null) {
            profile.joinOrFutureWaitNanos += System.nanoTime() - waitStart;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("threaded physics engine is closed");
        }
    }

    private static int defaultWorkerCount() {
        return Math.max(MIN_WORKER_COUNT, Runtime.getRuntime().availableProcessors() - 1);
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
