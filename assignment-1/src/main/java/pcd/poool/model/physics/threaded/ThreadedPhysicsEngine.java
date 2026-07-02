package pcd.poool.model.physics.threaded;

import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialCollisionDetector;
import pcd.poool.model.physics.common.SpatialGridSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pcd.poool.model.common.math.V2d;

/**
 * Platform-threaded physics stepper for large Poool configurations.
 *
 * <p>The engine keeps board mutation under the same single-writer ownership
 * rule used by the sequential engine: callers enter {@link #step(Board, long)}
 * and the method synchronizes on the board for the whole tick. Inside that
 * critical section, long-lived worker threads perform independent computation
 * on disjoint ball chunks. The controller thread then merges worker results and
 * applies deterministic aggregate collision deltas.
 *
 * <p>The current parallel phases are:
 *
 * <ol>
 *   <li>ball integration: friction, movement, and boundary constraints;</li>
 *   <li>spatial-grid population for broad-phase collision detection;</li>
 *   <li>deterministic merge/deduplication of candidate pairs;</li>
 *   <li>parallel accumulated-impulse collision resolution and game-event
 *       recording.</li>
 * </ol>
 */
public class ThreadedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_WORKER_COUNT = 1;
    private static final int MIN_CELLS_PER_WORKER_FOR_PARALLEL_PAIR_COLLECTION = 8;
    private static final long MIN_PAIR_COMBINATIONS_FOR_PARALLEL_COLLECTION = 4_096L;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final long maxStepMillis;
    private final PhysicsWorker[] workers;
    private final Tuning tuning;
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
        this(workerCount, PhysicsDefaults.FIXED_STEP_MILLIS, Tuning.defaultTuning());
    }

    /**
     * Creates a threaded physics engine.
     *
     * @param workerCount number of long-lived worker platform threads
     * @param maxStepMillis maximum duration of one internal physics sub-step
     */
    public ThreadedPhysicsEngine(int workerCount, long maxStepMillis) {
        this(workerCount, maxStepMillis, Tuning.defaultTuning());
    }

    ThreadedPhysicsEngine(int workerCount, long maxStepMillis, Tuning tuning) {
        if (workerCount < MIN_WORKER_COUNT) {
            throw new IllegalArgumentException("workerCount must be >= 1");
        }
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        if (tuning == null) {
            throw new IllegalArgumentException("tuning must not be null");
        }
        this.maxStepMillis = maxStepMillis;
        this.tuning = tuning;
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
        long stateReadStart = profile == null ? 0 : System.nanoTime();
        var activeBalls = activeBalls(board);
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - stateReadStart;
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
        }, profile);
        if (profile != null) {
            long integrationNanos = System.nanoTime() - integrationStart;
            profile.integrationNanos += integrationNanos;
            profile.movementNanos += integrationNanos;
        }

        long holeStart = profile == null ? 0 : System.nanoTime();
        board.applyHoleInteractions();
        if (profile != null) {
            profile.holeInteractionNanos += System.nanoTime() - holeStart;
        }

        long stateReadContinueStart = profile == null ? 0 : System.nanoTime();
        var collisionBalls = board.getCollisionBalls();
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - stateReadContinueStart;
            profile.collisionBalls += collisionBalls.size();
        }
        var detection = detectCollisionPairsPacked(collisionBalls, profile);
        long resolutionStart = profile == null ? 0 : System.nanoTime();
        resolveCollisionsWithAccumulatedImpulses(board, collisionBalls, detection.encodedPairs(), profile);
        if (profile != null) {
            profile.candidatePairs += detection.size();
            profile.collisionResolutionNanos += System.nanoTime() - resolutionStart;
        }
    }

    private void resolveCollisionsWithAccumulatedImpulses(
            Board board,
            List<Ball> balls,
            long[] packedPairs,
            StepProfileAccumulator profile) {
        if (packedPairs.length == 0) {
            return;
        }
        if (packedPairs.length < tuning.minPairsForParallelRounds()) {
            resolveCollisionsSequentially(board, balls, packedPairs, profile);
            return;
        }
        if (packedPairs.length < tuning.minPairsForAccumulatedSolver()) {
            resolveCollisionsInParallelRounds(board, balls, packedPairs, profile);
            return;
        }

        long collisionResolutionStart = profile == null ? 0 : System.nanoTime();
        var localDeltas = new CollisionDeltaAccumulator[Math.min(workers.length, packedPairs.length)];
        runRanges(packedPairs.length, (from, to, workerIndex) -> {
            var accumulator = new CollisionDeltaAccumulator(balls.size());
            for (int i = from; i < to; i++) {
                accumulator.add(computeCollisionContribution(balls, packedPairs[i]));
            }
            localDeltas[workerIndex] = accumulator;
        }, profile);
        if (profile != null) {
            profile.collisionResolutionNanos += System.nanoTime() - collisionResolutionStart;
        }

        long mergeApplyStart = profile == null ? 0 : System.nanoTime();
        var merged = new CollisionDeltaAccumulator(balls.size());
        for (var delta : localDeltas) {
            if (delta != null) {
                merged.merge(delta);
            }
        }

        for (var pair : merged.contactPairs) {
            board.recordCollision(balls.get(pair.firstIndex()), balls.get(pair.secondIndex()));
        }

        applyMergedDeltas(balls, merged, profile);
        if (profile != null) {
            long mergeApplyNanos = System.nanoTime() - mergeApplyStart;
            profile.mergeApplyNanos += mergeApplyNanos;
            profile.aggregationNanos += mergeApplyNanos;
        }
    }

    private void resolveCollisionsInParallelRounds(
            Board board,
            List<Ball> balls,
            long[] packedPairs,
            StepProfileAccumulator profile) {
        long collisionResolutionStart = profile == null ? 0 : System.nanoTime();
        for (long packedPair : packedPairs) {
            board.recordCollision(balls.get(firstIndex(packedPair)), balls.get(secondIndex(packedPair)));
        }

        for (var round : buildCollisionRounds(packedPairs, balls.size())) {
            runRanges(round.size(), (from, to, workerIndex) -> {
                for (int i = from; i < to; i++) {
                    long packedPair = round.encodedPairs()[i];
                    Ball.resolveCollision(
                            balls.get(firstIndex(packedPair)),
                            balls.get(secondIndex(packedPair)));
                }
            }, profile);
        }
        if (profile != null) {
            profile.collisionResolutionNanos += System.nanoTime() - collisionResolutionStart;
        }
    }

    private void resolveCollisionsSequentially(
            Board board,
            List<Ball> balls,
            long[] packedPairs,
            StepProfileAccumulator profile) {
        long collisionResolutionStart = profile == null ? 0 : System.nanoTime();
        var merged = new CollisionDeltaAccumulator(balls.size());
        for (long packedPair : packedPairs) {
            merged.add(computeCollisionContribution(balls, packedPair));
        }
        if (profile != null) {
            profile.collisionResolutionNanos += System.nanoTime() - collisionResolutionStart;
        }

        long mergeApplyStart = profile == null ? 0 : System.nanoTime();
        for (var pair : merged.contactPairs) {
            board.recordCollision(balls.get(pair.firstIndex()), balls.get(pair.secondIndex()));
        }
        applyMergedDeltas(balls, merged, profile);
        if (profile != null) {
            long mergeApplyNanos = System.nanoTime() - mergeApplyStart;
            profile.mergeApplyNanos += mergeApplyNanos;
            profile.aggregationNanos += mergeApplyNanos;
        }
    }

    private void applyMergedDeltas(List<Ball> balls, CollisionDeltaAccumulator merged, StepProfileAccumulator profile) {
        if (balls.size() < tuning.minBallsForParallelDeltaApply()) {
            for (int i = 0; i < balls.size(); i++) {
                var positionDelta = new V2d(merged.positionDeltaX[i], merged.positionDeltaY[i]);
                var velocityDelta = new V2d(merged.velocityDeltaX[i], merged.velocityDeltaY[i]);
                balls.get(i).translate(positionDelta);
                balls.get(i).addVelocity(velocityDelta);
            }
            return;
        }
        runRanges(balls.size(), (from, to, workerIndex) -> {
            for (int i = from; i < to; i++) {
                var positionDelta = new V2d(merged.positionDeltaX[i], merged.positionDeltaY[i]);
                var velocityDelta = new V2d(merged.velocityDeltaX[i], merged.velocityDeltaY[i]);
                balls.get(i).translate(positionDelta);
                balls.get(i).addVelocity(velocityDelta);
            }
        }, profile);
    }

    private CollisionContribution computeCollisionContribution(List<Ball> balls, long packedPair) {
        int firstIndex = firstIndex(packedPair);
        int secondIndex = secondIndex(packedPair);
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
        if (relativeVelocityAlongNormal <= 0) {
            double impulse = -(1 + PhysicsDefaults.RESTITUTION_FACTOR) * relativeVelocityAlongNormal
                    / (1.0 / a.getMass() + 1.0 / b.getMass());
            firstVelocityDeltaX = -(impulse / a.getMass()) * nx;
            firstVelocityDeltaY = -(impulse / a.getMass()) * ny;
            secondVelocityDeltaX = (impulse / b.getMass()) * nx;
            secondVelocityDeltaY = (impulse / b.getMass()) * ny;
        }

        return new CollisionContribution(
                new CollisionPair(firstIndex, secondIndex),
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

    List<SpatialCollisionDetector.Pair> detectCollisionPairs(List<Ball> balls) {
        return detectCollisionPairsPacked(balls, null).toPairList();
    }

    private DetectionResult detectCollisionPairsPacked(List<Ball> balls, StepProfileAccumulator profile) {
        if (balls.size() < 2) {
            return DetectionResult.empty();
        }

        long collisionDetectionStart = profile == null ? 0 : System.nanoTime();
        double cellSize = SpatialGridSupport.computeCellSize(balls);
        @SuppressWarnings("unchecked")
        Map<SpatialGridSupport.GridCell, List<Integer>>[] localGrids = new Map[workers.length];

        long localGridStart = profile == null ? 0 : System.nanoTime();
        runRanges(balls.size(), (from, to, workerIndex) -> {
            long workerStart = profile == null ? 0 : System.nanoTime();
            var localGrid = new HashMap<SpatialGridSupport.GridCell, List<Integer>>();
            for (int i = from; i < to; i++) {
                for (var cell : SpatialGridSupport.occupiedCells(balls.get(i), cellSize)) {
                    localGrid.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(i);
                }
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
        var mergedGrid = new HashMap<SpatialGridSupport.GridCell, List<Integer>>();
        for (var localGrid : localGrids) {
            if (localGrid == null) {
                continue;
            }
            for (var entry : localGrid.entrySet()) {
                mergedGrid.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        if (profile != null) {
            profile.gridMergeNanos += System.nanoTime() - aggregationStart;
        }

        long pairStart = profile == null ? 0 : System.nanoTime();
        int maxCellOccupancy = 0;
        var mergedIndexes = new ArrayList<List<Integer>>(mergedGrid.size());
        long estimatedPairCombinations = 0L;
        for (var indexes : mergedGrid.values()) {
            mergedIndexes.add(indexes);
            maxCellOccupancy = Math.max(maxCellOccupancy, indexes.size());
            estimatedPairCombinations += estimatePairCombinations(indexes.size());
        }
        long[] packedPairs = collectPairs(mergedIndexes, estimatedPairCombinations);
        if (profile != null) {
            profile.pairCollectionNanos += System.nanoTime() - pairStart;
            profile.collisionDetectionNanos += System.nanoTime() - collisionDetectionStart;
            profile.mergedCells += mergedGrid.size();
            profile.maxCellOccupancy = Math.max(profile.maxCellOccupancy, maxCellOccupancy);
            profile.aggregationNanos += System.nanoTime() - aggregationStart;
        }
        return new DetectionResult(packedPairs, mergedGrid.size(), maxCellOccupancy);
    }

    private long[] collectPairs(List<List<Integer>> cellIndexes, long estimatedPairCombinations) {
        if (cellIndexes.isEmpty()) {
            return new long[0];
        }
        if (!shouldParallelizePairCollection(cellIndexes.size(), estimatedPairCombinations)) {
            return collectPairsSequentially(cellIndexes);
        }

        int workerCount = Math.min(workers.length, cellIndexes.size());
        LongPairSet[] localPairs = new LongPairSet[workerCount];
        runRanges(cellIndexes.size(), (from, to, workerIndex) -> {
            var pairs = new LongPairSet();
            for (int i = from; i < to; i++) {
                collectPairs(cellIndexes.get(i), pairs);
            }
            localPairs[workerIndex] = pairs;
        }, null);

        var mergedPairs = new LongPairSet();
        for (int i = 0; i < workerCount; i++) {
            if (localPairs[i] != null) {
                mergedPairs.addAll(localPairs[i]);
            }
        }
        return mergedPairs.toSortedArray();
    }

    private long[] collectPairsSequentially(List<List<Integer>> cellIndexes) {
        var pairs = new LongPairSet();
        for (var indexes : cellIndexes) {
            collectPairs(indexes, pairs);
        }
        return pairs.toSortedArray();
    }

    private List<CollisionRound> buildCollisionRounds(long[] packedPairs, int ballCount) {
        if (packedPairs.length == 0) {
            return List.of();
        }

        long[] remainingPairs = Arrays.copyOf(packedPairs, packedPairs.length);
        int remainingCount = remainingPairs.length;
        var rounds = new ArrayList<CollisionRound>();
        while (remainingCount > 0) {
            boolean[] usedBalls = new boolean[ballCount];
            long[] roundPairs = new long[remainingCount];
            long[] nextRemainingPairs = new long[remainingCount];
            int roundCount = 0;
            int nextRemainingCount = 0;

            for (int i = 0; i < remainingCount; i++) {
                long packedPair = remainingPairs[i];
                int first = firstIndex(packedPair);
                int second = secondIndex(packedPair);
                if (!usedBalls[first] && !usedBalls[second]) {
                    roundPairs[roundCount++] = packedPair;
                    usedBalls[first] = true;
                    usedBalls[second] = true;
                } else {
                    nextRemainingPairs[nextRemainingCount++] = packedPair;
                }
            }

            rounds.add(new CollisionRound(Arrays.copyOf(roundPairs, roundCount)));
            remainingPairs = Arrays.copyOf(nextRemainingPairs, nextRemainingCount);
            remainingCount = nextRemainingCount;
        }
        return List.copyOf(rounds);
    }

    private boolean shouldParallelizePairCollection(int cellCount, long estimatedPairCombinations) {
        if (workers.length == 1 || cellCount <= 1) {
            return false;
        }
        if (estimatedPairCombinations < MIN_PAIR_COMBINATIONS_FOR_PARALLEL_COLLECTION) {
            return false;
        }
        return cellCount >= workers.length * MIN_CELLS_PER_WORKER_FOR_PARALLEL_PAIR_COLLECTION;
    }

    private long estimatePairCombinations(int occupancy) {
        if (occupancy < 2) {
            return 0L;
        }
        return (long) occupancy * (occupancy - 1) / 2L;
    }

    private void collectPairs(List<Integer> indexes, LongPairSet pairs) {
        for (int i = 0; i < indexes.size() - 1; i++) {
            for (int j = i + 1; j < indexes.size(); j++) {
                pairs.add(encodePair(indexes.get(i), indexes.get(j)));
            }
        }
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

    @FunctionalInterface
    private interface RangeTask {

        void run(int fromInclusive, int toExclusive, int workerIndex);
    }

    private record CollisionPair(int firstIndex, int secondIndex) {}

    private record CollisionContribution(
            CollisionPair pair,
            double firstPositionDeltaX,
            double firstPositionDeltaY,
            double firstVelocityDeltaX,
            double firstVelocityDeltaY,
            double secondPositionDeltaX,
            double secondPositionDeltaY,
            double secondVelocityDeltaX,
            double secondVelocityDeltaY) {}

    private record DetectionResult(long[] encodedPairs, int mergedCells, int maxCellOccupancy) {

        private static DetectionResult empty() {
            return new DetectionResult(new long[0], 0, 0);
        }

        private int size() {
            return encodedPairs.length;
        }

        private List<SpatialCollisionDetector.Pair> toPairList() {
            if (encodedPairs.length == 0) {
                return List.of();
            }
            var pairs = new ArrayList<SpatialCollisionDetector.Pair>(encodedPairs.length);
            for (long packedPair : encodedPairs) {
                pairs.add(new SpatialCollisionDetector.Pair(firstIndex(packedPair), secondIndex(packedPair)));
            }
            return List.copyOf(pairs);
        }
    }

    private record CollisionRound(long[] encodedPairs) {

        private int size() {
            return encodedPairs.length;
        }
    }

    private static final class CollisionDeltaAccumulator {

        private final double[] positionDeltaX;
        private final double[] positionDeltaY;
        private final double[] velocityDeltaX;
        private final double[] velocityDeltaY;
        private final List<CollisionPair> contactPairs;

        private CollisionDeltaAccumulator(int ballCount) {
            positionDeltaX = new double[ballCount];
            positionDeltaY = new double[ballCount];
            velocityDeltaX = new double[ballCount];
            velocityDeltaY = new double[ballCount];
            contactPairs = new ArrayList<>();
        }

        private void add(CollisionContribution contribution) {
            if (contribution == null) {
                return;
            }
            var pair = contribution.pair();
            positionDeltaX[pair.firstIndex()] += contribution.firstPositionDeltaX();
            positionDeltaY[pair.firstIndex()] += contribution.firstPositionDeltaY();
            velocityDeltaX[pair.firstIndex()] += contribution.firstVelocityDeltaX();
            velocityDeltaY[pair.firstIndex()] += contribution.firstVelocityDeltaY();
            positionDeltaX[pair.secondIndex()] += contribution.secondPositionDeltaX();
            positionDeltaY[pair.secondIndex()] += contribution.secondPositionDeltaY();
            velocityDeltaX[pair.secondIndex()] += contribution.secondVelocityDeltaX();
            velocityDeltaY[pair.secondIndex()] += contribution.secondVelocityDeltaY();
            contactPairs.add(pair);
        }

        private void merge(CollisionDeltaAccumulator other) {
            for (int i = 0; i < positionDeltaX.length; i++) {
                positionDeltaX[i] += other.positionDeltaX[i];
                positionDeltaY[i] += other.positionDeltaY[i];
                velocityDeltaX[i] += other.velocityDeltaX[i];
                velocityDeltaY[i] += other.velocityDeltaY[i];
            }
            contactPairs.addAll(other.contactPairs);
        }
    }

    private static final class LongPairSet {

        private static final double MAX_LOAD_FACTOR = 0.5;

        private long[] table;
        private long[] values;
        private int size;
        private int usedSlots;

        private LongPairSet() {
            table = new long[32];
            values = new long[32];
        }

        private void add(long packedPair) {
            if (packedPair == 0) {
                throw new IllegalArgumentException("packedPair must be non-zero");
            }
            if ((usedSlots + 1.0) / table.length > MAX_LOAD_FACTOR) {
                resize();
            }
            insert(packedPair);
        }

        private void addAll(LongPairSet other) {
            for (int i = 0; i < other.size; i++) {
                add(other.values[i]);
            }
        }

        private long[] toSortedArray() {
            long[] packedPairs = Arrays.copyOf(values, size);
            Arrays.sort(packedPairs);
            return packedPairs;
        }

        private void insert(long packedPair) {
            int mask = table.length - 1;
            int slot = mix(packedPair) & mask;
            while (true) {
                long current = table[slot];
                if (current == 0) {
                    table[slot] = packedPair;
                    usedSlots++;
                    ensureValueCapacity(size + 1);
                    values[size++] = packedPair;
                    return;
                }
                if (current == packedPair) {
                    return;
                }
                slot = (slot + 1) & mask;
            }
        }

        private void resize() {
            long[] previousTable = table;
            table = new long[previousTable.length * 2];
            usedSlots = 0;
            for (long packedPair : previousTable) {
                if (packedPair != 0) {
                    reinsert(packedPair);
                }
            }
        }

        private void reinsert(long packedPair) {
            int mask = table.length - 1;
            int slot = mix(packedPair) & mask;
            while (table[slot] != 0) {
                slot = (slot + 1) & mask;
            }
            table[slot] = packedPair;
            usedSlots++;
        }

        private void ensureValueCapacity(int requiredCapacity) {
            if (requiredCapacity <= values.length) {
                return;
            }
            int newCapacity = values.length * 2;
            while (newCapacity < requiredCapacity) {
                newCapacity *= 2;
            }
            values = Arrays.copyOf(values, newCapacity);
        }

        private int mix(long value) {
            long mixed = value ^ (value >>> 33);
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= mixed >>> 33;
            mixed *= 0xc4ceb9fe1a85ec53L;
            mixed ^= mixed >>> 33;
            return (int) mixed;
        }
    }

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
     * @param syncTimeMillis total coordination time in milliseconds
     * @param aggregationTimeMillis result aggregation and merge time in milliseconds
     * @param taskSubmissionTimeMillis task assignment time in milliseconds
     * @param joinOrFutureWaitMillis worker wait time in milliseconds
     * @param lockAcquisitions estimated number of lock acquisitions
     * @param submittedTasks estimated number of submitted tasks
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
            double stateReadMillis,
            double partitionMillis,
            double movementMillis,
            double holeInteractionMillis,
            double collisionDetectionMillis,
            double collisionResolutionMillis,
            double mergeApplyMillis,
            double integrationMillis,
            double localGridBuildMillis,
            double gridMergeMillis,
            double pairCollectionMillis,
            double syncTimeMillis,
            double aggregationTimeMillis,
            double taskSubmissionTimeMillis,
            double joinOrFutureWaitMillis,
            long lockAcquisitions,
            long submittedTasks,
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
        private long stateReadNanos;
        private long partitionNanos;
        private long movementNanos;
        private long integrationNanos;
        private long holeInteractionNanos;
        private long collisionDetectionNanos;
        private long collisionResolutionNanos;
        private long mergeApplyNanos;
        private long localGridBuildNanos;
        private long gridMergeNanos;
        private long pairCollectionNanos;
        private long syncNanos;
        private long aggregationNanos;
        private long taskSubmissionNanos;
        private long joinOrFutureWaitNanos;
        private long lockAcquisitions;
        private long submittedTasks;

        private StepProfileAccumulator(int workerCount) {
            integrationWorkerNanos = new long[workerCount];
            localGridWorkerNanos = new long[workerCount];
            integrationWorkerItems = new int[workerCount];
            localGridWorkerItems = new int[workerCount];
        }

        private StepProfile toProfile() {
            long measuredSyncNanos = taskSubmissionNanos + joinOrFutureWaitNanos;
            return new StepProfile(
                    activeBalls,
                    collisionBalls,
                    candidatePairs,
                    mergedCells,
                    maxCellOccupancy,
                    stateReadNanos / NANOS_PER_MILLISECOND,
                    partitionNanos / NANOS_PER_MILLISECOND,
                    movementNanos / NANOS_PER_MILLISECOND,
                    holeInteractionNanos / NANOS_PER_MILLISECOND,
                    collisionDetectionNanos / NANOS_PER_MILLISECOND,
                    collisionResolutionNanos / NANOS_PER_MILLISECOND,
                    mergeApplyNanos / NANOS_PER_MILLISECOND,
                    integrationNanos / NANOS_PER_MILLISECOND,
                    localGridBuildNanos / NANOS_PER_MILLISECOND,
                    gridMergeNanos / NANOS_PER_MILLISECOND,
                    pairCollectionNanos / NANOS_PER_MILLISECOND,
                    measuredSyncNanos / NANOS_PER_MILLISECOND,
                    aggregationNanos / NANOS_PER_MILLISECOND,
                    taskSubmissionNanos / NANOS_PER_MILLISECOND,
                    joinOrFutureWaitNanos / NANOS_PER_MILLISECOND,
                    lockAcquisitions,
                    submittedTasks,
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

    public record Tuning(
            int minPairsForParallelRounds,
            int minPairsForAccumulatedSolver,
            int minBallsForParallelDeltaApply) {

        public Tuning {
            if (minPairsForParallelRounds < 1) {
                throw new IllegalArgumentException("minPairsForParallelRounds must be >= 1");
            }
            if (minPairsForAccumulatedSolver < minPairsForParallelRounds) {
                throw new IllegalArgumentException(
                        "minPairsForAccumulatedSolver must be >= minPairsForParallelRounds");
            }
            if (minBallsForParallelDeltaApply < 1) {
                throw new IllegalArgumentException("minBallsForParallelDeltaApply must be >= 1");
            }
        }

        public static Tuning defaultTuning() {
            return new Tuning(32, 512, 128);
        }
    }
}
