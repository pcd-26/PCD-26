package pcd.poool.runtime;

import java.util.Objects;
import java.time.Duration;
import java.util.function.Predicate;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.PhysicsStepper;

/**
 * Strategy-independent game tick and publication boundary.
 *
 * <p>The caller owns the controller thread or executor task. This class owns
 * the model, serializes commands, advances one tick, and publishes immutable
 * snapshots for readers.
 */
public final class GameLoop {

    private final GameModel game;
    private final CommandMailbox commands = new CommandMailbox();
    private RuntimeGameSnapshot snapshot;

    public GameLoop(
            BoardConf boardConf, // Board layout and initial ball placement.
            PhysicsStepper physics, // Physics engine used for each tick.
            GameModel.StartupCountdown startupCountdown) { // Startup shoot lock policy.
        game = new GameModel(
                Objects.requireNonNull(boardConf, "boardConf"),
                Objects.requireNonNull(physics, "physics"),
                Objects.requireNonNull(startupCountdown, "startupCountdown"));
        snapshot = RuntimeGameSnapshot.from(game);
    }

    /** Executes all commands, advances physics, and publishes one snapshot. */
    public void tick(long tickMillis) {
        // Apply queued shots and other pending commands first so the tick sees
        // the latest user intent.
        drainCommands();
        // Only advance the simulation while the match is still running.
        if (!game.snapshot().isFinished()) {
            game.step(tickMillis);
        }
        // Publish a fresh immutable snapshot for readers after the tick.
        publishSnapshot();
    }

    public CommandMailbox.Receipt<Boolean> shootHuman(V2d velocity) {
        return commands.submit(model -> model.shootHuman(velocity), false);
    }

    public CommandMailbox.Receipt<Boolean> shootBot() {
        return commands.submit(GameModel::shootBot, false);
    }

    public synchronized RuntimeGameSnapshot snapshot() {
        return snapshot;
    }

    public synchronized RuntimeGameSnapshot awaitSnapshot(
            Predicate<RuntimeGameSnapshot> condition,
            Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!condition.test(snapshot)) {
            // Wait until the snapshot changes or the timeout expires.
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new IllegalStateException("snapshot condition was not reached before timeout");
            }
            wait(remaining);
        }
        return snapshot;
    }

    /** Rejects pending commands and publishes the final state. */
    public void close() {
        // Stop accepting new commands before exposing the final snapshot.
        commands.close();
        // Make the last state visible to readers waiting on a condition.
        publishSnapshot();
    }

    // Applies every queued command to the live model under single-writer control.
    private void drainCommands() {
        commands.drain(game);
    }

    // Rebuilds the immutable snapshot and wakes any waiter.
    private synchronized void publishSnapshot() {
        snapshot = RuntimeGameSnapshot.from(game);
        notifyAll();
    }
}
