package pcd.dttt.client;

import java.util.List;

/**
 * Client-side Controller interface representing the Tic-Tac-Toe client logic.
 * Decouples the presentation layer (GUI/CLI) from the network/RMI stubs and exports,
 * satisfying the Single Responsibility and Dependency Inversion Principles.
 */
public interface GameController {
    /**
     * Connects to the remote matchmaking Lobby.
     *
     * @param host the server IP address
     * @param port the server RMI port
     * @param serviceName the RMI binding name used by the lobby service
     * @param playerName the nickname of the player connecting
     * @throws Exception if connection or stub export fails
     */
    void connect(String host, int port, String serviceName, String playerName) throws Exception;

    /**
     * Creates a new game room with the given name.
     *
     * @param gameName the unique name of the game room to create
     * @throws Exception if an RMI or matchmaking error occurs
     */
    void createGame(String gameName) throws Exception;

    /**
     * Joins an existing game room.
     *
     * @param gameName the name of the game room to join
     * @throws Exception if the room is full, not found, or RMI fails
     */
    void joinGame(String gameName) throws Exception;

    /**
     * Retrieves the list of names of rooms currently waiting for an opponent.
     *
     * @return a List of room names
     * @throws Exception if RMI queries fail
     */
    List<String> getWaitingGames() throws Exception;

    /**
     * Places a mark on the active board.
     *
     * @param row zero-indexed row (0, 1, or 2)
     * @param col zero-indexed column (0, 1, or 2)
     * @throws Exception if it is not the player's turn, coordinate is invalid/occupied, or RMI fails
     */
    void makeMove(int row, int col) throws Exception;

    /**
     * Leaves the active match, terminating it.
     *
     * @throws Exception if RMI call fails
     */
    void leaveGame() throws Exception;

    /**
     * Registers a listener to handle game event callbacks (started, updated, opponent left).
     *
     * @param listener the GameEventListener to register
     */
    void registerEventListener(GameEventListener listener);

    /**
     * Disconnects the controller, unexporting stubs and releasing references.
     */
    void disconnect();

    /**
     * Gets the nickname of the current player.
     *
     * @return the player name
     */
    String getPlayerName();
}
