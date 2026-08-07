package pcd.poool.runtime;

import java.util.Objects;
import java.time.Duration;
import java.util.function.Predicate;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.PhysicsStepper;

/**
 * Strategy-independent game tick.
 *
 * <p>The caller owns the controller thread or executor task. This class owns
 * the model, serializes commands, advances one tick, and publishes a snapshot.
 */
public final class GameLoop {

    private final GameModel game;
    private final CommandMailbox commands = new CommandMailbox();
    private RuntimeGameSnapshot snapshot;

    public GameLoop(
            BoardConf boardConf,
            PhysicsStepper physics,
            GameModel.StartupCountdown startupCountdown) {
        game = new GameModel(
                Objects.requireNonNull(boardConf, "boardConf"),
                Objects.requireNonNull(physics, "physics"),
                Objects.requireNonNull(startupCountdown, "startupCountdown"));
        snapshot = RuntimeGameSnapshot.from(game);
    }

    /** Executes all commands, advances physics, and publishes one snapshot. */
    public void tick(long tickMillis) {
        drainCommands();
        if (!game.snapshot().isFinished()) {
            game.step(tickMillis);
        }
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
        commands.close();
        publishSnapshot();
    }

    private void drainCommands() {
        commands.drain(game);
    }

    private synchronized void publishSnapshot() {
        snapshot = RuntimeGameSnapshot.from(game);
        notifyAll();
    }
}
