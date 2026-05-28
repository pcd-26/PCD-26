package pcd.poool.model.physics;

/**
 * Deterministic physics stepper for board state updates.
 *
 * <p>The engine is deliberately passive: runners decide whether steps are
 * executed sequentially, by a dedicated platform thread, or by tasks.
 */
public class PhysicsEngine {

    private final SpatialCollisionDetector collisionDetector;
    private final long maxStepMillis;

    public PhysicsEngine() {
        this(PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    public PhysicsEngine(long maxStepMillis) {
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        this.collisionDetector = new SpatialCollisionDetector();
        this.maxStepMillis = maxStepMillis;
    }

    /**
     * Advances the board by the given elapsed time.
     *
     * <p>The elapsed time is split into bounded sub-steps to reduce numerical
     * instability when a caller provides a large delta. The whole operation is
     * synchronized on the board so direct callers preserve the single-writer
     * ownership rule used by the concurrent architecture.
     */
    public void step(Board board, long elapsedMillis) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        synchronized (board) {
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
        if (board.getPlayerBallEntity() != null) {
            board.getPlayerBallEntity().updateState(dt, bounds);
        }
        for (var ball : board.getSmallBallEntities()) {
            ball.updateState(dt, bounds);
        }

        board.applyHoleInteractions();

        var allBalls = board.getCollisionBalls();
        for (var pair : collisionDetector.detectCollisionPairs(allBalls)) {
            Ball.resolveCollision(allBalls.get(pair.firstIndex()), allBalls.get(pair.secondIndex()));
        }
    }
}
