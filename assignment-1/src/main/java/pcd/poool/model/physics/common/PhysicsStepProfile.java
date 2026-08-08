package pcd.poool.model.physics.common;

/** Per-step timing summary for a physics engine. */
public record PhysicsStepProfile(
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
