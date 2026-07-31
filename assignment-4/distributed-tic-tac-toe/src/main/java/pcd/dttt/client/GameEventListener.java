package pcd.dttt.client;

import pcd.dttt.common.BoardState;

/**
 * A local callback listener interface. CLI and GUI clients implement this
 * to react to game events received via RMI from the server.
 */
public interface GameEventListener {
    /**
     * Called when the game starts.
     *
     * @param initialState the initial board state snapshot when the game starts
     */
    void onGameStarted(BoardState initialState);

    /**
     * Called when a new board state is available.
     *
     * @param newState the updated board state snapshot
     */
    void onGameUpdated(BoardState newState);

    /**
     * Called when the opponent player disconnects or leaves.
     *
     * @param opponentName the nickname of the opponent who left
     */
    void onOpponentLeft(String opponentName);
}
