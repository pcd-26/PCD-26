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
 *   <li>group candidate collisions into deterministic non-conflicting rounds
 *       and resolve each round with executor tasks. Collisions in the same
 *       round never mutate the same ball, while independent contacts are
 *       scheduled as early as possible.</li>
 * </ol>
 *
 * <p>The coordinator thread is the caller of {@link #step(Board, long)} and
 * owns global board state. Worker tasks mutate only disjoint balls inside a
 * collision round or disjoint ball ranges in other phases.
 */
public class TaskBasedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_POOL_SIZE = 1;
    private static final int MIN_ITEMS_PER_PARALLEL_TASK = 64;
    private static final int MIN_PAIRS_FOR_ACCUMULATED_SOLVER = 512;
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
        // The board monitor spans the full tick; tasks only run inside that
        // critical section on disjoint ranges or private accumulators.
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
                activeBalls.get(i).ball().updateState(dt, bounds);
            }
            if (profile != null) {
                profile.integrationWorkerItems[workerIndex] += to - from;
                profile.integrationWorkerNanos[workerIndex] += System.nanoTime() - workerStart;
            }
            return null;
        }, profile);
        if (profile != null) {
            long integrationNanos = System.nanoTime() - integrationStart;
            profile.integrationNanos += integrationNanos;
            profile.movementNanos += integrationNanos;
        }

        long holeStart = profile == null ? 0 : System.nanoTime();
        var holeInteractions = detectHoleInteractions(board.getHoles(), activeBalls, profile);
        board.applyHoleInteractions(holeInteractions);
        if (profile != null) {
            profile.holeInteractionNanos += System.nanoTime() - holeStart;
        }

        long collisionReadStart = profile == null ? 0 : System.nanoTime();
        var collisionBalls = board.getCollisionBalls();
        if (profile != null) {
            profile.stateReadNanos += System.nanoTime() - collisionReadStart;
            profile.collisionBalls += collisionBalls.size();
        }
        var collisionPairs = detectCollisionPairsPacked(collisionBalls, profile);
        if (profile != null) {
            profile.candidatePairs += collisionPairs.size();
        }
        long resolutionStart = profile == null ? 0 : System.nanoTime();
        resolveCollisionsInParallelRounds(board, collisionBalls, collisionPairs, profile);
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

    private Board.HoleInteractions detectHoleInteractions(
            List<Hole> holes,
            List<ActiveBall> activeBalls,
            StepProfileAccumulator profile) {
        if (holes.isEmpty() || activeBalls.isEmpty()) {
            return new Board.HoleInteractions(false, false, List.of());
        }

        long holeDetectionStart = profile == null ? 0 : System.nanoTime();
        // The tasks only report local pocketing facts; the coordinator merges
        // the booleans and pocketed-ball list in a deterministic order.
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
        }, profile);

        long aggregationStart = profile == null ? 0 : System.nanoTime();
        boolean playerBallPocketed = false;
        boolean botBallPocketed = false;
        var pocketedSmallBalls = new ArrayList<Ball>();
        for (var result : results) {
            playerBallPocketed |= result.playerBallPocketed();
            botBallPocketed |= result.botBallPocketed();
            pocketedSmallBalls.addAll(result.pocketedSmallBalls());
        }
        if (profile != null) {
            profile.holeInteractionNanos += System.nanoTime() - holeDetectionStart;
            profile.aggregationNanos += System.nanoTime() - aggregationStart;
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

        long collisionDetectionStart = profile == null ? 0 : System.nanoTime();
        double cellSize = SpatialGridSupport.computeCellSize(balls);
        @SuppressWarnings("unchecked")
        java.util.Map<SpatialGridSupport.GridCell, List<Integer>>[] localGrids =
                new java.util.Map[poolSize];

        long localGridStart = profile == null ? 0 : System.nanoTime();
        // Each task writes only to a private local grid, then the coordinator
        // merges the buckets before any deterministic ordering happens.
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
        }, profile);
        if (profile != null) {
            profile.localGridBuildNanos += System.nanoTime() - localGridStart;
        }

        long aggregationStart = profile == null ? 0 : System.nanoTime();
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
            profile.gridMergeNanos += System.nanoTime() - aggregationStart;
        }

        long pairStart = profile == null ? 0 : System.nanoTime();
        var pairs = new LongPairSet();
        int maxCellOccupancy = 0;
        // Sorting the packed pairs keeps collision resolution deterministic,
        // even though the candidate generation ran in parallel.
        for (var indexes : mergedGrid.values()) {
            maxCellOccupancy = Math.max(maxCellOccupancy, indexes.size());
            collectPairs(indexes, pairs);
        }
        if (profile != null) {
            profile.pairCollectionNanos += System.nanoTime() - pairStart;
            profile.collisionDetectionNanos += System.nanoTime() - collisionDetectionStart;
            profile.mergedCells += mergedGrid.size();
            profile.maxCellOccupancy = Math.max(profile.maxCellOccupancy, maxCellOccupancy);
            profile.aggregationNanos += System.nanoTime() - aggregationStart;
        }
        long[] orderedPairs = pairs.toArray();
        java.util.Arrays.sort(orderedPairs);
        return new CollisionPairs(orderedPairs, mergedGrid.size(), maxCellOccupancy);
    }

    private void resolveCollisionsInParallelRounds(
            Board board,
            List<Ball> balls,
            CollisionPairs pairs,
            StepProfileAccumulator profile) {
        if (pairs.size() == 0) {
            return;
        }
        if (pairs.size() >= MIN_PAIRS_FOR_ACCUMULATED_SOLVER) {
            resolveCollisionsWithAccumulatedImpulses(board, balls, pairs, profile);
            return;
        }

        long collisionResolutionStart = profile == null ? 0 : System.nanoTime();
        for (long pair : pairs.encodedPairs()) {
            var first = balls.get(firstIndex(pair));
            var second = balls.get(secondIndex(pair));
            board.recordCollision(first, second);
        }

        // Rounds keep the write set disjoint: a ball appears in at most one
        // pair per round, so worker tasks can resolve them in parallel safely.
        for (var round : buildCollisionRounds(pairs, balls.size())) {
            runRanges(round.size(), (from, to, workerIndex) -> {
                for (int i = from; i < to; i++) {
                    long pair = round.encodedPairs()[i];
                    Ball.resolveCollision(balls.get(firstIndex(pair)), balls.get(secondIndex(pair)));
                }
                return null;
            }, profile);
        }
        if (profile != null) {
            profile.collisionResolutionNanos += System.nanoTime() - collisionResolutionStart;
        }
    }

    private void resolveCollisionsWithAccumulatedImpulses(
            Board board,
            List<Ball> balls,
            CollisionPairs pairs,
            StepProfileAccumulator profile) {
        long collisionResolutionStart = profile == null ? 0 : System.nanoTime();
        var localAccumulators = new CollisionAccumulator[Math.min(poolSize, pairs.size())];
        runRanges(pairs.size(), (from, to, workerIndex) -> {
            var accumulator = new CollisionAccumulator(balls.size());
            for (int i = from; i < to; i++) {
                accumulator.addCollision(balls, pairs.encodedPairs()[i]);
            }
            localAccumulators[workerIndex] = accumulator;
            return null;
        }, profile);
        if (profile != null) {
            profile.collisionResolutionNanos += System.nanoTime() - collisionResolutionStart;
        }

        long mergeApplyStart = profile == null ? 0 : System.nanoTime();
        var merged = new CollisionAccumulator(balls.size());
        for (var accumulator : localAccumulators) {
            if (accumulator != null) {
                merged.merge(accumulator);
            }
        }

        // The final board writes are serialized by ball index, but each index
        // is touched once only, so the apply phase stays race-free.
        for (int i = 0; i < merged.contactPairCount; i++) {
            long pair = merged.contactPairs[i];
            board.recordCollision(balls.get(firstIndex(pair)), balls.get(secondIndex(pair)));
        }

        runRanges(balls.size(), (from, to, workerIndex) -> {
            long workerStart = profile == null ? 0 : System.nanoTime();
            for (int i = from; i < to; i++) {
                balls.get(i).translate(new V2d(merged.positionDeltaX[i], merged.positionDeltaY[i]));
                balls.get(i).addVelocity(new V2d(merged.velocityDeltaX[i], merged.velocityDeltaY[i]));
            }
            if (profile != null) {
                profile.applyWorkerItems[workerIndex] += to - from;
                profile.applyWorkerNanos[workerIndex] += System.nanoTime() - workerStart;
            }
            return null;
        }, profile);
        if (profile != null) {
            long mergeApplyNanos = System.nanoTime() - mergeApplyStart;
            profile.mergeApplyNanos += mergeApplyNanos;
            profile.aggregationNanos += mergeApplyNanos;
        }
    }

    List<List<SpatialCollisionDetector.Pair>> buildCollisionRounds(
            List<SpatialCollisionDetector.Pair> pairs,
            int ballCount) {
        long[] encodedPairs = new long[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            var pair = pairs.get(i);
            encodedPairs[i] = encodePair(pair.firstIndex(), pair.secondIndex());
        }

        var rounds = buildCollisionRounds(new CollisionPairs(encodedPairs, 0, 0), ballCount);
        var result = new ArrayList<List<SpatialCollisionDetector.Pair>>(rounds.size());
        for (var round : rounds) {
            var decodedRound = new ArrayList<SpatialCollisionDetector.Pair>(round.size());
            for (long packedPair : round.encodedPairs()) {
                decodedRound.add(new SpatialCollisionDetector.Pair(firstIndex(packedPair), secondIndex(packedPair)));
            }
            result.add(List.copyOf(decodedRound));
        }
        return List.copyOf(result);
    }

    private List<CollisionRound> buildCollisionRounds(CollisionPairs pairs, int ballCount) {
        if (pairs.size() == 0) {
            return List.of();
        }

        long[] remainingPairs = pairs.encodedPairs();
        int remainingCount = remainingPairs.length;
        var rounds = new ArrayList<CollisionRound>();
        while (remainingCount > 0) {
            boolean[] usedBalls = new boolean[ballCount];
            long[] roundPairs = new long[remainingCount];
            long[] nextRemainingPairs = new long[remainingCount];
            int roundCount = 0;
            int nextRemainingCount = 0;

            for (int i = 0; i < remainingCount; i++) {
                long pair = remainingPairs[i];
                int first = firstIndex(pair);
                int second = secondIndex(pair);
                if (!usedBalls[first] && !usedBalls[second]) {
                    roundPairs[roundCount++] = pair;
                    usedBalls[first] = true;
                    usedBalls[second] = true;
                } else {
                    nextRemainingPairs[nextRemainingCount++] = pair;
                }
            }

            rounds.add(new CollisionRound(java.util.Arrays.copyOf(roundPairs, roundCount)));
            remainingPairs = nextRemainingPairs;
            remainingCount = nextRemainingCount;
        }
        return rounds;
    }

    private <T> List<T> runRanges(int itemCount, RangeTask<T> rangeTask, StepProfileAccumulator profile) {
        if (itemCount == 0) {
            return List.of();
        }

        long partitionStart = profile == null ? 0 : System.nanoTime();
        var ranges = buildRangeChunks(itemCount);
        if (profile != null) {
            profile.partitionNanos += System.nanoTime() - partitionStart;
        }
        if (ranges.size() == 1) {
            var range = ranges.get(0);
            var result = new ArrayList<T>(1);
            result.add(rangeTask.run(range.fromInclusive(), range.toExclusive(), 0));
            return result;
        }
        try {
            // Futures are the phase barrier: the coordinator can only merge or
            // apply shared state after every range has completed.
            long submissionStart = profile == null ? 0 : System.nanoTime();
            var futures = new ArrayList<Future<T>>(ranges.size());
            for (var range : ranges) {
                futures.add(executor.submit(() -> rangeTask.run(range.fromInclusive(), range.toExclusive(), range.workerIndex())));
            }
            if (profile != null) {
                profile.taskSubmissionNanos += System.nanoTime() - submissionStart;
                profile.submittedTasks += ranges.size();
                profile.lockAcquisitions += ranges.size() + 1L;
            }
            long waitStart = profile == null ? 0 : System.nanoTime();
            var results = new ArrayList<T>(futures.size());
            for (var future : futures) {
                results.add(future.get());
            }
            if (profile != null) {
                profile.joinOrFutureWaitNanos += System.nanoTime() - waitStart;
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
        // Cap task count so the scheduler does not pay more overhead than the
        // work it is asked to parallelize.
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

    private record CollisionRound(long[] encodedPairs) {

        private int size() {
            return encodedPairs.length;
        }
    }

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
     * @param syncTimeMillis total coordination time in milliseconds
     * @param aggregationTimeMillis result aggregation and merge time in milliseconds
     * @param taskSubmissionTimeMillis task submission time in milliseconds
     * @param joinOrFutureWaitMillis worker wait time in milliseconds
     * @param lockAcquisitions estimated number of lock acquisitions
     * @param submittedTasks estimated number of submitted tasks
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
            List<Double> applyWorkerMillis,
            List<Integer> integrationWorkerItems,
            List<Integer> localGridWorkerItems,
            List<Integer> applyWorkerItems) {}

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
            applyWorkerNanos = new long[workerCount];
            integrationWorkerItems = new int[workerCount];
            localGridWorkerItems = new int[workerCount];
            applyWorkerItems = new int[workerCount];
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
