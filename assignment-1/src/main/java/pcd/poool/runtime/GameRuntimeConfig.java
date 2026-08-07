package pcd.poool.runtime;

import pcd.poool.model.game.GameModel;
import pcd.poool.model.physics.common.PhysicsDefaults;

/** Configuration shared by both concurrent game runtimes. */
public record GameRuntimeConfig(
        long tickMillis,
        boolean botEnabled,
        long botThinkTimeMillis,
        int physicsWorkerCount,
        GameModel.StartupCountdown startupCountdown) {

    public GameRuntimeConfig(long tickMillis, boolean botEnabled, long botThinkTimeMillis) {
        this(tickMillis, botEnabled, botThinkTimeMillis, defaultPhysicsWorkerCount());
    }

    public GameRuntimeConfig(
            long tickMillis,
            boolean botEnabled,
            long botThinkTimeMillis,
            int physicsWorkerCount) {
        this(
                tickMillis,
                botEnabled,
                botThinkTimeMillis,
                physicsWorkerCount,
                GameModel.StartupCountdown.enabledDefault());
    }

    public GameRuntimeConfig(
            long tickMillis,
            boolean botEnabled,
            long botThinkTimeMillis,
            GameModel.StartupCountdown startupCountdown) {
        this(tickMillis, botEnabled, botThinkTimeMillis, defaultPhysicsWorkerCount(), startupCountdown);
    }

    public GameRuntimeConfig {
        if (tickMillis <= 0) {
            throw new IllegalArgumentException("tickMillis must be > 0");
        }
        if (botThinkTimeMillis < 0) {
            throw new IllegalArgumentException("botThinkTimeMillis must be >= 0");
        }
        if (physicsWorkerCount < 1) {
            throw new IllegalArgumentException("physicsWorkerCount must be >= 1");
        }
        if (startupCountdown == null) {
            throw new IllegalArgumentException("startupCountdown must not be null");
        }
    }

    public static GameRuntimeConfig defaultConfig() {
        return new GameRuntimeConfig(
                PhysicsDefaults.FIXED_STEP_MILLIS,
                true,
                600,
                defaultPhysicsWorkerCount());
    }

    public static GameRuntimeConfig withoutBot() {
        return new GameRuntimeConfig(
                PhysicsDefaults.FIXED_STEP_MILLIS,
                false,
                0,
                defaultPhysicsWorkerCount(),
                GameModel.StartupCountdown.disabled());
    }

    private static int defaultPhysicsWorkerCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }
}
