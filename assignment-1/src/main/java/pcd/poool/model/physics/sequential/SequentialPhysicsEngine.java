package pcd.poool.model.physics.sequential;

import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialCollisionDetector;

/** Deterministic physics stepper for board state updates. */
public class SequentialPhysicsEngine implements PhysicsStepper {

    private final SpatialCollisionDetector collisionDetector;
    private final long maxStepMillis;

    /** Creates a physics engine using the default maximum sub-step duration. */
    public SequentialPhysicsEngine() {
        this(PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    /** Creates a physics engine. */
    public SequentialPhysicsEngine(long maxStepMillis) {
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        this.collisionDetector = new SpatialCollisionDetector();
        this.maxStepMillis = maxStepMillis;
    }

    /** Advances the board by splitting large deltas into smaller sub-steps. */
    @Override
    public void step(Board board, long elapsedMillis) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        long remaining = elapsedMillis;
        while (remaining > 0) {
            long dt = Math.min(maxStepMillis, remaining);
            stepOnce(board, dt);
            remaining -= dt;
        }
    }

    private void stepOnce(Board board, long dt) {
        var bounds = board.getBounds();
        // Move first, then pocket, then resolve contacts.
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
