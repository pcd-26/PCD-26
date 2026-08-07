package pcd.poool.runtime;

import pcd.poool.model.game.GameModel;

// Command executed against a controller-owned game model.
@FunctionalInterface
public interface GameCommand {

    // Executes the command on the controller-owned game model.
    void execute(GameModel game);

    // Notifies the command that it was discarded before execution.
    default void reject() {
    }
}
