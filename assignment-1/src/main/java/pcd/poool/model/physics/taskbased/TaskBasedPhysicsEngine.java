package pcd.poool.model.physics.taskbased;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.Hole;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialCollisionDetector;
import pcd.poool.model.physics.common.SpatialGridSupport;
import pcd.poool.model.common.math.V2d;

/**
 * Task-based physics stepper for board state updates.
 *
 * <p>The engine keeps the same single-writer ownership rule used by the other
 * physics implementations: callers synchronize on the board for the whole
 * update, while internal task execution is limited to disjoint work ranges or
 * read-only coordination phases.
 *
 * <p>Task pipeline:
 * <ol>
 *   <li>integrate balls in parallel over disjoint index ranges;</li>
 *   <li>detect hole interactions in parallel and merge local results on the
 *       coordinator thread;</li>
 *   <li>build one local spatial grid per task, merge the grids on the
 *       coordinator thread, then generate deterministic candidate pairs;</li>
 *   <li>compute collision contributions in parallel over pair ranges, merge
 *       local accumulators on the coordinator thread, and finally apply the
 *       deltas to the board-owned balls.</li>
 * </ol>
 *
 * <p>The coordinator thread is the caller of {@link #step(Board, long)} and is
 * the only thread that mutates global board state or applies merged results.
 */
public class TaskBasedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_POOL_SIZE = 1;
    private static final int MIN_ITEMS_PER_PARALLEL_TASK = 64;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private final ExecutorService executor;
    private final long maxStepMillis;
    private final int poolSize;
    private boolean closed;

    /**
     * Creates a task-based physics engine using the default pool size.
     */
    public TaskBasedPhysicsEngine() {
        this(defaultPoolSize());
    }

    /**
     * Creates a task-based physics engine.
     *
     * @param poolSize number of executor workers available for task phases
     */
    public TaskBasedPhysicsEngine(int poolSize) {
        this(poolSize, PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    /**
     * Creates a task-based physics engine.
     *
     * @param poolSize number of executor workers available for task phases
     * @param maxStepMillis maximum duration of one internal physics sub-step
     */
    public TaskBasedPhysicsEngine(int poolSize, long maxStepMillis) {
        if (poolSize < MIN_POOL_SIZE) {
            throw new IllegalArgumentException("poolSize must be >= 1");
        }
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        this.poolSize = poolSize;
        this.maxStepMillis = maxStepMillis;
        this.executor = Executors.newFixedThreadPool(poolSize, runnable -> {
            var thread = new Thread(runnable);
            thread.setName("poool-task-physics-worker");
            thread.setDaemon(true);
            return thread;
        });
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
        StepProfileAccumulator profile = profilingEnabled ? new StepProfileAccumulator(poolSize) : null;
        synchronized (board) {
            ensureOpen();
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
     * Stops the executor and prevents further steps from being accepted.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
    }

    /**
     * Gets the configured executor pool size.
     *
     * @return number of executor workers available for task phases
     */
    public int poolSize() {
        return poolSize;
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
                activeBalls.get(i).ball().updateState(dt, bounds);
            }
            if (profile != null) {
                profile.integrationWorkerItems[workerIndex] += to - from;
                profile.integrationWorkerNanos[workerIndex] += System.nanoTime() - workerStart;
            }
            return null;
        });
        if (profile != null) {
            profile.integrationNanos += System.nanoTime() - integrationStart;
        }

        long holeStart = profile == null ? 0 : System.nanoTime();
        var holeInteractions = detectHoleInteractions(board.getHoles(), activeBalls);
        board.applyHoleInteractions(holeInteractions);
        if (profile != null) {
            profile.holeInteractionNanos += System.nanoTime() - holeStart;
        }

        var collisionBalls = board.getCollisionBalls();
        if (profile != null) {
            profile.collisionBalls += collisionBalls.size();
        }
        var collisionPairs = detectCollisionPairsPacked(collisionBalls, profile);
        if (profile != null) {
            profile.candidatePairs += collisionPairs.size();
        }
        long resolutionStart = profile == null ? 0 : System.nanoTime();
        resolveCollisionsDeterministically(board, collisionBalls, collisionPairs, profile);
        if (profile != null) {
            profile.collisionResolutionNanos += System.nanoTime() - resolutionStart;
        }
    }

    private List<ActiveBall> activeBalls(Board board) {
        var activeBalls = new ArrayList<ActiveBall>();
        var playerBall = board.getPlayerBallEntity();
        if (playerBall != null) {
            activeBalls.add(new ActiveBall(playerBall, BallRole.PLAYER));
        }
        var botBall = board.getBotBallEntity();
        if (botBall != null) {
            activeBalls.add(new ActiveBall(botBall, BallRole.BOT));
        }
        for (var ball : board.getSmallBallEntities()) {
            activeBalls.add(new ActiveBall(ball, BallRole.SMALL));
        }
        return activeBalls;
    }

    private Board.HoleInteractions detectHoleInteractions(List<Hole> holes, List<ActiveBall> activeBalls) {
        if (holes.isEmpty() || activeBalls.isEmpty()) {
            return new Board.HoleInteractions(false, false, List.of());
        }

        var results = runRanges(activeBalls.size(), (from, to, workerIndex) -> {
            boolean playerBallPocketed = false;
            boolean botBallPocketed = false;
            var pocketedSmallBalls = new ArrayList<Ball>();
            for (int i = from; i < to; i++) {
                var activeBall = activeBalls.get(i);
                if (isInsideAnyHole(activeBall.ball(), holes)) {
                    if (activeBall.role() == BallRole.PLAYER) {
                        playerBallPocketed = true;
                    } else if (activeBall.role() == BallRole.BOT) {
                        botBallPocketed = true;
                    } else {
                        pocketedSmallBalls.add(activeBall.ball());
                    }
                }
            }
            return new HoleTaskResult(playerBallPocketed, botBallPocketed, pocketedSmallBalls);
        });

        boolean playerBallPocketed = false;
        boolean botBallPocketed = false;
        var pocketedSmallBalls = new ArrayList<Ball>();
        for (var result : results) {
            playerBallPocketed |= result.playerBallPocketed();
            botBallPocketed |= result.botBallPocketed();
            pocketedSmallBalls.addAll(result.pocketedSmallBalls());
        }
        return new Board.HoleInteractions(playerBallPocketed, botBallPocketed, List.copyOf(pocketedSmallBalls));
    }

    List<SpatialCollisionDetector.Pair> detectCollisionPairs(List<Ball> balls) {
        return detectCollisionPairsPacked(balls, null).toPairList();
    }

    private CollisionPairs detectCollisionPairsPacked(
            List<Ball> balls,
            StepProfileAccumulator profile) {
        if (balls.size() < 2) {
            return CollisionPairs.empty();
        }

        double cellSize = SpatialGridSupport.computeCellSize(balls);
        @SuppressWarnings("unchecked")
        java.util.Map<SpatialGridSupport.GridCell, List<Integer>>[] localGrids =
                new java.util.Map[poolSize];

        long localGridStart = profile == null ? 0 : System.nanoTime();
        runRanges(balls.size(), (from, to, workerIndex) -> {
            long workerStart = profile == null ? 0 : System.nanoTime();
            var localGrid = new java.util.HashMap<SpatialGridSupport.GridCell, List<Integer>>();
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
            return localGrid;
        });
        if (profile != null) {
            profile.localGridBuildNanos += System.nanoTime() - localGridStart;
        }

        long mergeStart = profile == null ? 0 : System.nanoTime();
        var mergedGrid = new java.util.HashMap<SpatialGridSupport.GridCell, List<Integer>>();
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
        var pairs = new LongPairSet();
        int maxCellOccupancy = 0;
        for (var indexes : mergedGrid.values()) {
            maxCellOccupancy = Math.max(maxCellOccupancy, indexes.size());
            collectPairs(indexes, pairs);
        }
        if (profile != null) {
            profile.pairCollectionNanos += System.nanoTime() - pairStart;
            profile.mergedCells += mergedGrid.size();
            profile.maxCellOccupancy = Math.max(profile.maxCellOccupancy, maxCellOccupancy);
        }
        long[] orderedPairs = pairs.toArray();
        java.util.Arrays.sort(orderedPairs);
        return new CollisionPairs(orderedPairs, mergedGrid.size(), maxCellOccupancy);
    }

    private void resolveCollisionsDeterministically(
            Board board,
            List<Ball> balls,
            CollisionPairs pairs,
            StepProfileAccumulator profile) {
        if (pairs.size() == 0) {
            return;
        }

        for (long pair : pairs.encodedPairs()) {
            var first = balls.get(firstIndex(pair));
            var second = balls.get(secondIndex(pair));
            board.recordCollision(first, second);
            Ball.resolveCollision(first, second);
        }
    }

    private <T> List<T> runRanges(int itemCount, RangeTask<T> rangeTask) {
        if (itemCount == 0) {
            return List.of();
        }

        var ranges = buildRangeChunks(itemCount);
        if (ranges.size() == 1) {
            var range = ranges.get(0);
            var result = new ArrayList<T>(1);
            result.add(rangeTask.run(range.fromInclusive(), range.toExclusive(), 0));
            return result;
        }
        var tasks = new ArrayList<Callable<T>>(ranges.size());
        for (var range : ranges) {
            tasks.add(() -> {
                return rangeTask.run(range.fromInclusive(), range.toExclusive(), range.workerIndex());
            });
        }
        return invokeAll(tasks);
    }

    private <T> List<T> invokeAll(List<Callable<T>> tasks) {
        try {
            List<Future<T>> futures = executor.invokeAll(tasks);
            var results = new ArrayList<T>(futures.size());
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("task-based physics step interrupted", ex);
        } catch (ExecutionException ex) {
            throw rethrowTaskFailure(ex.getCause());
        }
    }

    private boolean isInsideAnyHole(Ball ball, List<Hole> holes) {
        for (var hole : holes) {
            if (hole.contains(ball.getPos())) {
                return true;
            }
        }
        return false;
    }

    private void collectPairs(List<Integer> indexes, LongPairSet pairs) {
        if (indexes == null) {
            return;
        }
        for (int i = 0; i < indexes.size() - 1; i++) {
            for (int j = i + 1; j < indexes.size(); j++) {
                pairs.add(encodePair(indexes.get(i), indexes.get(j)));
            }
        }
    }

    private List<RangeChunk> buildRangeChunks(int itemCount) {
        int taskCountByWorkSize = Math.max(1, itemCount / MIN_ITEMS_PER_PARALLEL_TASK);
        int workerCount = Math.min(Math.min(poolSize, itemCount), taskCountByWorkSize);
        int baseChunk = itemCount / workerCount;
        int remainder = itemCount % workerCount;
        var chunks = new ArrayList<RangeChunk>(workerCount);
        int from = 0;
        for (int i = 0; i < workerCount; i++) {
            int chunkSize = baseChunk + (i < remainder ? 1 : 0);
            int to = from + chunkSize;
            chunks.add(new RangeChunk(from, to, i));
            from = to;
        }
        return List.copyOf(chunks);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("task-based physics engine is closed");
        }
    }

    private RuntimeException rethrowTaskFailure(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("task-based physics step failed", cause);
    }

    private static int defaultPoolSize() {
        return Math.max(MIN_POOL_SIZE, Runtime.getRuntime().availableProcessors());
    }

    @FunctionalInterface
    private interface RangeTask<T> {

        T run(int fromInclusive, int toExclusive, int workerIndex);
    }

    private record RangeChunk(int fromInclusive, int toExclusive, int workerIndex) {}

    private record HoleTaskResult(
            boolean playerBallPocketed,
            boolean botBallPocketed,
            List<Ball> pocketedSmallBalls) {}

    private record CollisionPairs(long[] encodedPairs, int mergedCells, int maxCellOccupancy) {

        private static CollisionPairs empty() {
            return new CollisionPairs(new long[0], 0, 0);
        }

        private int size() {
            return encodedPairs.length;
        }

        private List<SpatialCollisionDetector.Pair> toPairList() {
            if (encodedPairs.length == 0) {
                return List.of();
            }
            long[] orderedPairs = java.util.Arrays.copyOf(encodedPairs, encodedPairs.length);
            java.util.Arrays.sort(orderedPairs);
            var pairs = new ArrayList<SpatialCollisionDetector.Pair>(orderedPairs.length);
            for (long packedPair : orderedPairs) {
                pairs.add(new SpatialCollisionDetector.Pair(firstIndex(packedPair), secondIndex(packedPair)));
            }
            return pairs;
        }
    }

    private record ActiveBall(Ball ball, BallRole role) {}

    private enum BallRole {
        PLAYER,
        BOT,
        SMALL
    }

    /**
     * Immutable per-step profiling data for the task-based physics pipeline.
     *
     * @param activeBalls number of balls integrated across all internal sub-steps
     * @param collisionBalls number of balls considered for collision detection
     * @param candidatePairs number of candidate collision pairs generated
     * @param mergedCells number of populated cells in the merged spatial grid
     * @param maxCellOccupancy maximum number of balls registered in one cell
     * @param integrationMillis total integration time in milliseconds
     * @param holeInteractionMillis hole interaction time in milliseconds
     * @param localGridBuildMillis local per-task grid-build time in milliseconds
     * @param gridMergeMillis merged-grid assembly time in milliseconds
     * @param pairCollectionMillis candidate-pair generation and sorting time in milliseconds
     * @param collisionResolutionMillis collision resolution time in milliseconds
     * @param integrationWorkerMillis per-task integration time in milliseconds
     * @param localGridWorkerMillis per-task local-grid build time in milliseconds
     * @param applyWorkerMillis per-task final application time in milliseconds
     * @param integrationWorkerItems per-task ball counts integrated
     * @param localGridWorkerItems per-task ball counts used for local-grid population
     * @param applyWorkerItems per-task ball counts written in the final apply phase
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
            List<Double> applyWorkerMillis,
            List<Integer> integrationWorkerItems,
            List<Integer> localGridWorkerItems,
            List<Integer> applyWorkerItems) {}

    private static final class CollisionAccumulator {

        private final double[] positionDeltaX;
        private final double[] positionDeltaY;
        private final double[] velocityDeltaX;
        private final double[] velocityDeltaY;
        private long[] contactPairs;
        private int contactPairCount;

        private CollisionAccumulator(int ballCount) {
            positionDeltaX = new double[ballCount];
            positionDeltaY = new double[ballCount];
            velocityDeltaX = new double[ballCount];
            velocityDeltaY = new double[ballCount];
            contactPairs = new long[16];
        }

        private void addCollision(List<Ball> balls, long packedPair) {
            int firstIndex = firstIndex(packedPair);
            int secondIndex = secondIndex(packedPair);
            var a = balls.get(firstIndex);
            var b = balls.get(secondIndex);

            double dx = b.getPos().x() - a.getPos().x();
            double dy = b.getPos().y() - a.getPos().y();
            double dist = Math.hypot(dx, dy);
            double minD = a.getRadius() + b.getRadius();

            if (dist >= minD) {
                return;
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

            positionDeltaX[firstIndex] += -nx * firstPositionCorrection;
            positionDeltaY[firstIndex] += -ny * firstPositionCorrection;
            velocityDeltaX[firstIndex] += firstVelocityDeltaX;
            velocityDeltaY[firstIndex] += firstVelocityDeltaY;
            positionDeltaX[secondIndex] += nx * secondPositionCorrection;
            positionDeltaY[secondIndex] += ny * secondPositionCorrection;
            velocityDeltaX[secondIndex] += secondVelocityDeltaX;
            velocityDeltaY[secondIndex] += secondVelocityDeltaY;
            appendContactPair(packedPair);
        }

        private void merge(CollisionAccumulator other) {
            for (int i = 0; i < positionDeltaX.length; i++) {
                positionDeltaX[i] += other.positionDeltaX[i];
                positionDeltaY[i] += other.positionDeltaY[i];
                velocityDeltaX[i] += other.velocityDeltaX[i];
                velocityDeltaY[i] += other.velocityDeltaY[i];
            }
            if (other.contactPairCount == 0) {
                return;
            }
            ensureContactCapacity(contactPairCount + other.contactPairCount);
            System.arraycopy(other.contactPairs, 0, contactPairs, contactPairCount, other.contactPairCount);
            contactPairCount += other.contactPairCount;
        }

        private void appendContactPair(long packedPair) {
            ensureContactCapacity(contactPairCount + 1);
            contactPairs[contactPairCount++] = packedPair;
        }

        private void ensureContactCapacity(int requiredCapacity) {
            if (requiredCapacity <= contactPairs.length) {
                return;
            }
            int newCapacity = contactPairs.length;
            while (newCapacity < requiredCapacity) {
                newCapacity *= 2;
            }
            contactPairs = java.util.Arrays.copyOf(contactPairs, newCapacity);
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

        private long[] toArray() {
            return java.util.Arrays.copyOf(values, size);
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
            values = java.util.Arrays.copyOf(values, newCapacity);
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

    private static final class StepProfileAccumulator {

        private final long[] integrationWorkerNanos;
        private final long[] localGridWorkerNanos;
        private final long[] applyWorkerNanos;
        private final int[] integrationWorkerItems;
        private final int[] localGridWorkerItems;
        private final int[] applyWorkerItems;
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
            applyWorkerNanos = new long[workerCount];
            integrationWorkerItems = new int[workerCount];
            localGridWorkerItems = new int[workerCount];
            applyWorkerItems = new int[workerCount];
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
                    toMillisList(applyWorkerNanos),
                    toIntList(integrationWorkerItems),
                    toIntList(localGridWorkerItems),
                    toIntList(applyWorkerItems));
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
}
