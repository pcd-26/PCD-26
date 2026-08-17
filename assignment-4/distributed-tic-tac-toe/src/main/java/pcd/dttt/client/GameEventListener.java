package pcd.dttt.client;

import pcd.dttt.common.BoardState;

// Receives game events forwarded from the callback stub.
public interface GameEventListener {
    // Handles the initial state when the second player joins.
    void onGameStarted(BoardState initialState);

    // Handles every board update sent by the server.
    void onGameUpdated(BoardState updatedState);

    // Handles opponent disconnection or explicit leave.
    void onOpponentLeft(String opponentName);
}
