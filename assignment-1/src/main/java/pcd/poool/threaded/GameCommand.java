package pcd.poool.threaded;

import pcd.poool.model.game.GameModel;

/**
 * Command executed by the threaded game controller on its owned game model.
 */
@FunctionalInterface
interface GameCommand {

    /**
     * Executes the command on the controller-owned game model.
     *
     * @param game model owned by the controller thread
     */
    void execute(GameModel game);

    /**
     * Notifies the command that it was discarded before execution, typically
     * because the runtime is shutting down.
     */
    default void reject() {
    }
}
