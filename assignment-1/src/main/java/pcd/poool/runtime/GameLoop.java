package pcd.poool.runtime;

import java.util.Objects;
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
    private final CommandQueueMonitorSupport<GameCommand> commands = new CommandQueueMonitorSupport<>();
    private final SnapshotStoreSupport<RuntimeGameSnapshot> snapshots;

    public GameLoop(
            BoardConf boardConf,
            PhysicsStepper physics,
            GameModel.StartupCountdown startupCountdown) {
        game = new GameModel(
                Objects.requireNonNull(boardConf, "boardConf"),
                Objects.requireNonNull(physics, "physics"),
                Objects.requireNonNull(startupCountdown, "startupCountdown"));
        snapshots = new SnapshotStoreSupport<>(RuntimeGameSnapshot.from(game));
    }

    /** Executes all commands, advances physics, and publishes one snapshot. */
    public void tick(long tickMillis) {
        drainCommands();
        if (!game.snapshot().isFinished()) {
            game.step(tickMillis);
        }
        publishSnapshot();
    }

    public CommandReceiptSupport<Boolean> shootHuman(V2d velocity) {
        return CommandSubmissionSupport.submit(commands, model -> model.shootHuman(velocity), false);
    }

    public CommandReceiptSupport<Boolean> shootBot() {
        return CommandSubmissionSupport.submit(commands, GameModel::shootBot, false);
    }

    public RuntimeGameSnapshot snapshot() {
        return snapshots.get();
    }

    public SnapshotStoreSupport<RuntimeGameSnapshot> snapshots() {
        return snapshots;
    }

    /** Rejects pending commands and publishes the final state. */
    public void close() {
        commands.close();
        publishSnapshot();
    }

    private void drainCommands() {
        GameCommand command;
        while ((command = commands.poll()) != null) {
            command.execute(game);
        }
    }

    private void publishSnapshot() {
        snapshots.publish(RuntimeGameSnapshot.from(game));
    }
}
