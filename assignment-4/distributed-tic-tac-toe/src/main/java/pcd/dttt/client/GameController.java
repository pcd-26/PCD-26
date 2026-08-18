package pcd.dttt.client;

import java.util.List;

// High-level client API used by GUI and CLI.
public interface GameController {
    // Opens the lobby connection and exports the callback object.
    void connect(String host, int port, String serviceName, String playerName) throws Exception;

    // Creates a new game room.
    void createGame(String gameName) throws Exception;

    // Joins an existing game room.
    void joinGame(String gameName) throws Exception;

    // Lists all rooms still waiting for an opponent.
    List<String> getWaitingGames() throws Exception;

    // Sends a move for the active room.
    void makeMove(int row, int col) throws Exception;

    // Leaves the active room.
    void leaveGame() throws Exception;

    // Registers a local listener for game events.
    void registerEventListener(GameEventListener listener);

    // Releases the remote connection and callback stub.
    void disconnect();

    // Returns the local player name.
    String getPlayerName();
}
