package pcd.poool.model.physics.sequential;

import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialCollisionDetector;

/**
 * Deterministic physics stepper for board state updates.
 *
 * <p>The engine is deliberately passive: runners decide whether steps are
 * executed sequentially, by a dedicated platform thread, or by tasks.
 */
public class PhysicsEngine implements PhysicsStepper {

    private final SpatialCollisionDetector collisionDetector;
    private final long maxStepMillis;

    /**
     * Creates a physics engine using the default maximum sub-step duration.
     */
    public PhysicsEngine() {
        this(PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    /**
     * Creates a physics engine.
     *
     * @param maxStepMillis maximum duration of one internal physics sub-step
     */
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
     *
     * @param board board to mutate
     * @param elapsedMillis elapsed time in milliseconds
     */
    @Override
    public void step(Board board, long elapsedMillis) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        // The board stays single-writer for the whole step.
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
        // Movement first, then pocketing, then pairwise contacts: the order
        // keeps the sequential baseline deterministic and reproducible.
        if (board.getPlayerBallEntity() != null) {
            board.getPlayerBallEntity().updateState(dt, bounds);
        }
        if (board.getBotBallEntity() != null) {
            board.getBotBallEntity().updateState(dt, bounds);
        }
        for (var ball : board.getSmallBallEntities()) {
            ball.updateState(dt, bounds);
        }

        board.applyHoleInteractions();

        var allBalls = board.getCollisionBalls();
        for (var pair : collisionDetector.detectCollisionPairs(allBalls)) {
            var first = allBalls.get(pair.firstIndex());
            var second = allBalls.get(pair.secondIndex());
            board.recordCollision(first, second);
            Ball.resolveCollision(first, second);
        }
    }
}
