package pcd.dttt.client;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.Game;
import pcd.dttt.common.Lobby;

/**
 * Concrete implementation of the client-side {@link GameController}.
 * Coordinates RMI registries, matchmaking lookups, player callback exports,
 * and handles forwarding network callbacks to registered UI listeners.
 */
public class GameControllerImpl implements GameController, GameEventListener {
    /** Default RMI registry host used when the caller does not provide one. */
    public static final String DEFAULT_REGISTRY_HOST = "localhost";

    /** Default RMI registry port used when the caller does not provide one. */
    public static final int DEFAULT_REGISTRY_PORT = 1099;

    /** The remote RMI reference to the matchmaking lobby. */
    private Lobby lobby;

    /** The remote RMI reference to the current active match room. */
    private Game currentGame;

    /** The remote player client stub instance exported for server callback invocation. */
    private PlayerClientImpl clientStub;

    /** Nickname of the local player. */
    private String playerName;

    /** Thread-safe list containing registered GUI/CLI event listeners. */
    private final List<GameEventListener> listeners = new ArrayList<>();

    /**
     * Constructs a new GameControllerImpl instance.
     */
    public GameControllerImpl() {}

    /**
     * {@inheritDoc}
     * Establishes the matchmaking Lobby lookup and exports the player's client callback stub.
     *
     * @param host the server IP address
     * @param port the server RMI port
     * @param serviceName the RMI binding name used by the lobby service
     * @param playerName the nickname of the player connecting
     * @throws Exception if lookup fails or stub exporting fails
     */
    @Override
    public void connect(String host, int port, String serviceName, String playerName) throws Exception {
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("Player name cannot be blank.");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("Service name cannot be blank.");
        }

        this.playerName = playerName;
        try {
            // Export our client callback object on an anonymous port.
            this.clientStub = new PlayerClientImpl(this);

            // Lookup matchmaking Lobby.
            Registry registry = LocateRegistry.getRegistry(host, port);
            this.lobby = (Lobby) registry.lookup(serviceName);
        } catch (Exception e) {
            cleanupClientStub();
            this.playerName = null;
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     * Contacts the Lobby to register a new Game room.
     *
     * @param gameName the unique name of the game room to create
     * @throws Exception if game room creation fails or RMI error occurs
     */
    @Override
    public void createGame(String gameName) throws Exception {
        this.currentGame = lobby().createGame(gameName, playerName, clientStub);
    }

    /**
     * {@inheritDoc}
     * Joins an existing game room.
     *
     * @param gameName the name of the game room to join
     * @throws Exception if room is full, not found, or RMI error occurs
     */
    @Override
    public void joinGame(String gameName) throws Exception {
        this.currentGame = lobby().joinGame(gameName, playerName, clientStub);
    }

    /**
     * {@inheritDoc}
     * Fetches list of active rooms in WAITING status.
     *
     * @return list of waiting game room names
     * @throws Exception if remote lookup fails
     */
    @Override
    public List<String> getWaitingGames() throws Exception {
        return lobby().getWaitingGames();
    }

    /**
     * {@inheritDoc}
     * Attempts to place a mark at the specified coordinates.
     *
     * @param row zero-indexed row (0, 1, or 2)
     * @param col zero-indexed column (0, 1, or 2)
     * @throws Exception if it is not this player's turn, coordinates are invalid, or RMI error occurs
     */
    @Override
    public void makeMove(int row, int col) throws Exception {
        if (currentGame == null) {
            throw new IllegalStateException("No active game room.");
        }
        currentGame.makeMove(playerName, row, col);
    }

    /**
     * {@inheritDoc}
     * Explicitly leaves the active match room.
     *
     * @throws Exception if RMI call fails
     */
    @Override
    public void leaveGame() throws Exception {
        if (currentGame != null) {
            try {
                currentGame.leaveGame(playerName);
            } finally {
                currentGame = null;
            }
        }
    }

    /**
     * {@inheritDoc}
     * Registers a local event listener (e.g. GUI or CLI) to receive callbacks.
     * Synchronizes on the listener collection to support thread-safe additions.
     *
     * @param listener the GameEventListener to register
     */
    @Override
    public void registerEventListener(GameEventListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    /**
     * {@inheritDoc}
     * Performs clean disconnection by leaving the game and unexporting RMI stubs.
     */
    @Override
    public void disconnect() {
        try {
            leaveGame();
        } catch (Exception e) {
            // Ignore failure on disconnection cleanup
        }
        cleanupClientStub();
        lobby = null;
    }

    /**
     * {@inheritDoc}
     *
     * @return the local player nickname
     */
    @Override
    public String getPlayerName() {
        return playerName;
    }

    // --- GameEventListener RMI Callback Forwarders ---

    /**
     * {@inheritDoc}
     * Forwards the match start callback to all registered local listeners.
     *
     * @param initialState the initial board state
     */
    @Override
    public void onGameStarted(BoardState initialState) {
        notifyListeners(listener -> listener.onGameStarted(initialState));
    }

    /**
     * {@inheritDoc}
     * Forwards the match update callback to all registered local listeners.
     *
     * @param newState the updated board state
     */
    @Override
    public void onGameUpdated(BoardState newState) {
        notifyListeners(listener -> listener.onGameUpdated(newState));
    }

    /**
     * {@inheritDoc}
     * Forwards the opponent left callback to all registered local listeners.
     *
     * @param opponentName the nickname of the opponent who left
     */
    @Override
    public void onOpponentLeft(String opponentName) {
        notifyListeners(listener -> listener.onOpponentLeft(opponentName));
    }

    /**
     * Unexports the locally exported callback stub, if present.
     * This method is intentionally idempotent so that connection failures and disconnects
     * can share the same cleanup path.
     */
    private void cleanupClientStub() {
        if (clientStub != null) {
            try {
                UnicastRemoteObject.unexportObject(clientStub, true);
            } catch (Exception e) {
                // Ignore cleanup failures
            }
            clientStub = null;
        }
    }

    /** Returns the lobby connection or fails fast when the client is disconnected. */
    private Lobby lobby() {
        if (lobby == null) {
            throw new IllegalStateException("Lobby connection is not established.");
        }
        return lobby;
    }

    /** Runs the given action for each registered listener under the listener lock. */
    private void notifyListeners(java.util.function.Consumer<GameEventListener> action) {
        synchronized (listeners) {
            for (GameEventListener listener : listeners) {
                action.accept(listener);
            }
        }
    }
}
