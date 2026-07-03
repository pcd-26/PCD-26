package pcd.poool.runtime;

import pcd.poool.model.game.GameModel;

/**
 * Command executed against a controller-owned game model.
 */
@FunctionalInterface
public interface GameCommand {

    /**
     * Executes the command on the controller-owned game model.
     *
     * @param game model owned by the controller thread or task
     */
    void execute(GameModel game);

    /**
     * Notifies the command that it was discarded before execution, typically
     * because the runtime is shutting down.
     */
    default void reject() {
    }
}
