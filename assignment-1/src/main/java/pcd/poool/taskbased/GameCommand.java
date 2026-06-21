package pcd.poool.taskbased;

import pcd.poool.model.game.GameModel;

@FunctionalInterface
interface GameCommand {

    void execute(GameModel game);

    default void reject() {
    }
}
