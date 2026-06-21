package pcd.poool.model.physics.threaded;

import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialCollisionDetector;
import pcd.poool.model.physics.common.SpatialGridSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        resolveCollisionsWithAccumulatedImpulses(board, collisionBalls, detection.pairs());
        if (profile != null) {
            profile.candidatePairs += detection.pairs().size();
            profile.collisionResolutionNanos += System.nanoTime() - resolutionStart;
        }
    }

    private void resolveCollisionsWithAccumulatedImpulses(
            Board board,
            List<Ball> balls,
            List<CollisionPair> pairs) {
        if (pairs.isEmpty()) {
            return;
        }

        var localDeltas = new CollisionDeltaAccumulator[Math.min(workers.length, pairs.size())];
        runRanges(pairs.size(), (from, to, workerIndex) -> {
            var accumulator = new CollisionDeltaAccumulator(balls.size());
            for (int i = from; i < to; i++) {
                accumulator.add(computeCollisionContribution(balls, pairs.get(i)));
            }
            localDeltas[workerIndex] = accumulator;
        });

        var merged = new CollisionDeltaAccumulator(balls.size());
        for (var delta : localDeltas) {
            if (delta != null) {
                merged.merge(delta);
            }
        }

        for (var pair : merged.contactPairs) {
            board.recordCollision(balls.get(pair.firstIndex()), balls.get(pair.secondIndex()));
        }

        runRanges(balls.size(), (from, to, workerIndex) -> {
            for (int i = from; i < to; i++) {
                var positionDelta = new V2d(merged.positionDeltaX[i], merged.positionDeltaY[i]);
                var velocityDelta = new V2d(merged.velocityDeltaX[i], merged.velocityDeltaY[i]);
                balls.get(i).translate(positionDelta);
                balls.get(i).addVelocity(velocityDelta);
            }
        });
    }

    private CollisionContribution computeCollisionContribution(List<Ball> balls, CollisionPair pair) {
        var a = balls.get(pair.firstIndex());
        var b = balls.get(pair.secondIndex());

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
                pair,
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

    private DetectionResult detectCollisionPairs(List<Ball> balls, StepProfileAccumulator profile) {
        if (balls.size() < 2) {
            return new DetectionResult(List.of(), 0, 0);
        }

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
        });
        if (profile != null) {
            profile.localGridBuildNanos += System.nanoTime() - localGridStart;
        }

        long mergeStart = profile == null ? 0 : System.nanoTime();
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

    private void collectPairs(List<Integer> indexes, Set<CollisionPair> pairs) {
        for (int i = 0; i < indexes.size() - 1; i++) {
            for (int j = i + 1; j < indexes.size(); j++) {
                pairs.add(new CollisionPair(
                        Math.min(indexes.get(i), indexes.get(j)),
                        Math.max(indexes.get(i), indexes.get(j))));
            }
        }
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

    private record DetectionResult(List<CollisionPair> pairs, int mergedCells, int maxCellOccupancy) {}

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
