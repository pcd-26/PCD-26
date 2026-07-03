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

/**
 * Platform-threaded physics engine focused on reducing the global
 * coordination cost of the collision phase.
 *
 * <p>The engine keeps the movement phase chunk-based but uses a
 * spatial-ownership collision pipeline:
 *
 * <ol>
 *   <li>workers build local spatial buckets for the balls they integrated;</li>
 *   <li>the controller merges buckets into one deterministic grid;</li>
 *   <li>grid cells are sorted and partitioned across workers;</li>
 *   <li>each worker resolves only the pairs canonically owned by its cells,
 *       avoiding a global candidate-pair materialization step;</li>
 *   <li>workers emit sparse collision deltas that are merged only on the
 *       touched ball indexes.</li>
 * </ol>
 *
 * <p>The board still has a single writer per tick: workers only process
 * private chunks or private accumulators, while the controller thread keeps
 * the serialized merge and apply phases.
 */
public class ThreadedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_WORKER_COUNT = 1;
    private static final int MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY = 256;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final long maxStepMillis;
    private final PhysicsWorker[] workers;
    private boolean closed;

    /**
     * Creates a threaded engine using a CPU-oriented default worker count.
     */
    public ThreadedPhysicsEngine() {
        this(defaultWorkerCount());
    }

    /**
     * Creates a threaded engine.
     *
     * @param workerCount number of long-lived worker platform threads
     */
    public ThreadedPhysicsEngine(int workerCount) {
        this(workerCount, PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    /**
     * Creates a threaded engine.
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
        this.workers = new PhysicsWorker[workerCount];
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
     * step.
     *
     * @param board board to mutate
     * @param elapsedMillis elapsed time in milliseconds
     * @return per-phase timings and workload counters for the executed step
     */
    public StepProfile profileStep(Board board, long elapsedMillis) {
        return stepInternal(board, elapsedMillis, true);
    }

    /**
     * Gets the number of worker threads owned by this engine.
     *
     * @return number of worker threads owned by this engine
     */
    public int workerCount() {
        return workers.length;
    }

    /**
     * Closes the owned workers and rejects future physics steps.
     */
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
        StepProfileAccumulator profile = profilingEnabled ? new StepProfileAccumulator(workers.length) : null;
        // One writer at a time: the board lock spans the full tick, while
        // workers only operate on private ranges inside that tick.
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

    private void stepOnce(Board board, long dt, StepProfileAccumulator profile) {
        var bounds = board.getBounds();
        long stateReadStart = profile == null ? 0 : System.nanoTime();
        var activeBalls = activeBalls(board);
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - stateReadStart;
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
        }, profile);
        if (profile != null) {
            long integrationNanos = System.nanoTime() - integrationStart;
            profile.movementNanos += integrationNanos;
        }

        long holeStart = profile == null ? 0 : System.nanoTime();
        board.applyHoleInteractions();
        if (profile != null) {
            profile.holeInteractionNanos += System.nanoTime() - holeStart;
        }

        long collisionStateReadStart = profile == null ? 0 : System.nanoTime();
        var collisionBalls = board.getCollisionBalls();
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - collisionStateReadStart;
        }
        if (collisionBalls.size() < 2) {
            return;
        }
        detectAndResolveCollisions(board, collisionBalls, profile);
    }

    private void detectAndResolveCollisions(Board board, List<Ball> balls, StepProfileAccumulator profile) {
        long collisionStart = profile == null ? 0 : System.nanoTime();
        double cellSize = computeOwnershipCellSize(balls);
        CenterCell[] centerCells = new CenterCell[balls.size()];

        @SuppressWarnings("unchecked")
        Map<SpatialGridSupport.GridCell, IntBag>[] localGrids = new Map[workers.length];
        long localGridStart = profile == null ? 0 : System.nanoTime();
        // Each worker builds a private grid so no shared bucket map is written
        // concurrently during the broad phase.
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
        if (profile != null) {
            profile.localGridBuildNanos += System.nanoTime() - localGridStart;
        }

        long aggregationStart = profile == null ? 0 : System.nanoTime();
        // The coordinator merges first, then sorts cells to keep pair ownership
        // and collision recording deterministic.
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
            profile.gridMergeNanos += System.nanoTime() - aggregationStart;
            profile.aggregationNanos += System.nanoTime() - aggregationStart;
            profile.maxCellOccupancy = Math.max(profile.maxCellOccupancy, maxCellOccupancy);
        }

        long resolutionStart = profile == null ? 0 : System.nanoTime();
        var localDeltas = new SparseCollisionDeltaAccumulator[Math.min(workers.length, orderedCells.size())];
        var localPairs = new LongBag[Math.min(workers.length, orderedCells.size())];
        // Workers compute collision contributions from the same tick-start
        // state, but the authoritative board is still untouched here.
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
            profile.candidatePairs += pairCount;
            profile.mergedCells += orderedCells.size();
            profile.collisionDetectionNanos += mergeApplyStart - collisionStart;
            profile.mergeApplyNanos += System.nanoTime() - mergeApplyStart;
            profile.aggregationNanos += System.nanoTime() - mergeApplyStart;
        }
    }

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

    private void applyMergedDeltas(List<Ball> balls, SparseCollisionDeltaAccumulator merged, StepProfileAccumulator profile) {
        if (merged.touchedCount() == 0) {
            return;
        }
        if (merged.touchedCount() < MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY) {
            // Small touch sets are cheaper to commit serially than to split
            // again into another worker barrier.
            applyMergedDeltasSequentially(balls, merged);
            return;
        }
        // Each touched ball index is assigned to exactly one worker here.
        runRanges(merged.touchedCount(), (from, to, workerIndex) -> {
            for (int i = from; i < to; i++) {
                int ballIndex = merged.touchedIndex(i);
                balls.get(ballIndex).translate(new V2d(merged.positionDeltaX(ballIndex), merged.positionDeltaY(ballIndex)));
                balls.get(ballIndex).addVelocity(new V2d(merged.velocityDeltaX(ballIndex), merged.velocityDeltaY(ballIndex)));
            }
        }, profile);
    }

    private void applyMergedDeltasSequentially(List<Ball> balls, SparseCollisionDeltaAccumulator merged) {
        for (int i = 0; i < merged.touchedCount(); i++) {
            int ballIndex = merged.touchedIndex(i);
            balls.get(ballIndex).translate(new V2d(merged.positionDeltaX(ballIndex), merged.positionDeltaY(ballIndex)));
            balls.get(ballIndex).addVelocity(new V2d(merged.velocityDeltaX(ballIndex), merged.velocityDeltaY(ballIndex)));
        }
    }

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

    private CenterCell computeCenterCell(Ball ball, double cellSize) {
        return new CenterCell(new SpatialGridSupport.GridCell(
                SpatialGridSupport.toCellCoordinate(ball.getPos().x(), cellSize),
                SpatialGridSupport.toCellCoordinate(ball.getPos().y(), cellSize)));
    }

    private double computeOwnershipCellSize(List<Ball> balls) {
        double maxRadius = balls.stream()
                .mapToDouble(Ball::getRadius)
                .max()
                .orElse(PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
        return Math.max(maxRadius * PhysicsDefaults.RADIUS_TO_DIAMETER, PhysicsDefaults.MIN_SPATIAL_CELL_SIZE);
    }

    private void runRanges(int itemCount, RangeTask rangeTask, StepProfileAccumulator profile) {
        if (itemCount == 0) {
            return;
        }
        int workerCount = Math.min(workers.length, itemCount);
        var completion = new WorkerCompletionMonitor(workerCount);
        long partitionStart = profile == null ? 0 : System.nanoTime();
        int baseChunk = itemCount / workerCount;
        int remainder = itemCount % workerCount;
        int from = 0;
        if (profile != null) {
            profile.partitionNanos += System.nanoTime() - partitionStart;
        }
        long submissionStart = profile == null ? 0 : System.nanoTime();
        for (int workerIndex = 0; workerIndex < workerCount; workerIndex++) {
            int chunkSize = baseChunk + (workerIndex < remainder ? 1 : 0);
            int start = from;
            int end = start + chunkSize;
            int assignedWorker = workerIndex;
            workers[workerIndex].assign(() -> rangeTask.run(start, end, assignedWorker), completion);
            from = end;
        }
        if (profile != null) {
            profile.taskSubmissionNanos += System.nanoTime() - submissionStart;
            profile.submittedTasks += workerCount;
            profile.lockAcquisitions += workerCount + 1L;
        }
        long waitStart = profile == null ? 0 : System.nanoTime();
        // This is the phase barrier: the coordinator cannot merge or apply
        // anything until every assigned worker has completed or failed.
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

    /**
     * Immutable per-step profiling data for the threaded physics pipeline.
     *
     * @param syncTimeMillis total coordination time in milliseconds
     * @param aggregationTimeMillis result aggregation and merge time in milliseconds
     * @param taskSubmissionTimeMillis task assignment time in milliseconds
     * @param joinOrFutureWaitMillis worker wait time in milliseconds
     * @param lockAcquisitions estimated number of lock acquisitions
     * @param submittedTasks estimated number of submitted tasks
     * @param stateReadMillis board snapshot read time in milliseconds
     * @param partitionMillis work partitioning time in milliseconds
     * @param movementMillis movement phase time in milliseconds
     * @param holeInteractionMillis hole interaction time in milliseconds
     * @param collisionDetectionMillis broad-phase detection time in milliseconds
     * @param collisionResolutionMillis collision resolution time in milliseconds
     * @param mergeApplyMillis delta merge and apply time in milliseconds
     */
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
        private long localGridBuildNanos;
        private long gridMergeNanos;
        private long aggregationNanos;
        private long taskSubmissionNanos;
        private long joinOrFutureWaitNanos;
        private long lockAcquisitions;
        private long submittedTasks;
        private int candidatePairs;
        private int mergedCells;
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
