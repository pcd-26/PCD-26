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
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialCollisionDetector;

/**
 * Task-based physics stepper for board state updates.
 *
 * <p>The engine keeps the same single-writer ownership rule used by the other
 * physics implementations: callers synchronize on the board for the whole
 * update, while internal task execution is limited to disjoint work ranges or
 * read-only coordination phases.
 */
public class TaskBasedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private static final int MIN_POOL_SIZE = 1;

    private final SpatialCollisionDetector collisionDetector;
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
        this.collisionDetector = new SpatialCollisionDetector();
        this.executor = Executors.newFixedThreadPool(poolSize, runnable -> {
            var thread = new Thread(runnable);
            thread.setName("poool-task-physics-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void step(Board board, long elapsedMillis) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        ensureOpen();
        synchronized (board) {
            ensureOpen();
            long remaining = elapsedMillis;
            while (remaining > 0) {
                long dt = Math.min(maxStepMillis, remaining);
                stepOnce(board, dt);
                remaining -= dt;
            }
        }
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

    private void stepOnce(Board board, long dt) {
        var bounds = board.getBounds();
        var activeBalls = activeBalls(board);
        runRanges(activeBalls.size(), (from, to) -> {
            for (int i = from; i < to; i++) {
                activeBalls.get(i).updateState(dt, bounds);
            }
        });

        board.applyHoleInteractions();

        var collisionBalls = board.getCollisionBalls();
        for (var pair : collisionDetector.detectCollisionPairs(collisionBalls)) {
            var first = collisionBalls.get(pair.firstIndex());
            var second = collisionBalls.get(pair.secondIndex());
            board.recordCollision(first, second);
            Ball.resolveCollision(first, second);
        }
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

    private void runRanges(int itemCount, RangeTask rangeTask) {
        if (itemCount == 0) {
            return;
        }

        var ranges = buildRangeChunks(itemCount);
        var tasks = new ArrayList<Callable<Void>>(ranges.size());
        for (var range : ranges) {
            tasks.add(() -> {
                rangeTask.run(range.fromInclusive(), range.toExclusive());
                return null;
            });
        }
        invokeAll(tasks);
    }

    private void invokeAll(List<Callable<Void>> tasks) {
        try {
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (var future : futures) {
                future.get();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("task-based physics step interrupted", ex);
        } catch (ExecutionException ex) {
            throw rethrowTaskFailure(ex.getCause());
        }
    }

    private List<RangeChunk> buildRangeChunks(int itemCount) {
        int workerCount = Math.min(poolSize, itemCount);
        int baseChunk = itemCount / workerCount;
        int remainder = itemCount % workerCount;
        var chunks = new ArrayList<RangeChunk>(workerCount);
        int from = 0;
        for (int i = 0; i < workerCount; i++) {
            int chunkSize = baseChunk + (i < remainder ? 1 : 0);
            int to = from + chunkSize;
            chunks.add(new RangeChunk(from, to));
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
    private interface RangeTask {

        void run(int fromInclusive, int toExclusive);
    }

    private record RangeChunk(int fromInclusive, int toExclusive) {}
}
