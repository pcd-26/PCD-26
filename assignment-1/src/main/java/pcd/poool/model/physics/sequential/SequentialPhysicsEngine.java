package pcd.poool.model.physics.sequential;

import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.common.SpatialCollisionDetector;

// Deterministic physics stepper for board updates.
public class SequentialPhysicsEngine implements PhysicsStepper {

    private final SpatialCollisionDetector collisionDetector;
    private final long maxStepMillis;

    // Uses the default sub-step duration.
    public SequentialPhysicsEngine() {
        this(PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    // Creates a physics engine with a custom sub-step duration.
    public SequentialPhysicsEngine(long maxStepMillis) {
        if (maxStepMillis <= 0) {
            throw new IllegalArgumentException("maxStepMillis must be > 0");
        }
        this.collisionDetector = new SpatialCollisionDetector();
        this.maxStepMillis = maxStepMillis;
    }

    // Advances the board in fixed-size chunks.
    @Override
    public void step(Board board, long elapsedMillis) {
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must be >= 0");
        }
        // Process the whole elapsed time in fixed-size chunks.
        long remaining = elapsedMillis;
        while (remaining > 0) {
            long dt = Math.min(maxStepMillis, remaining);
            stepOnce(board, dt);
            remaining -= dt;
        }
    }

    // Moves balls, applies holes, then resolves collisions.
    private void stepOnce(Board board, long dt) {
        var bounds = board.getBounds();

        if (board.getPlayerBallEntity() != null) {
            board.getPlayerBallEntity().updateState(dt, bounds);
        }
        if (board.getBotBallEntity() != null) {
            board.getBotBallEntity().updateState(dt, bounds);
        }
        for (var ball : board.getSmallBallEntities()) {
            ball.updateState(dt, bounds);
        }

        // Pocketing.
        board.applyHoleInteractions();

        // Collision resolution.
        var allBalls = board.getCandidateCollisionBalls();
        for (var pair : collisionDetector.detectCollisionPairs(allBalls)) {
            var first = allBalls.get(pair.firstIndex());
            var second = allBalls.get(pair.secondIndex());
            board.recordCollision(first, second);
            Ball.resolveCollision(first, second);
        }
    }
}
