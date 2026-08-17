package pcd.dttt.client;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.Game;
import pcd.dttt.common.Lobby;

// Connects the local UI to the remote lobby and remote game.
public class GameControllerImpl implements GameController, GameEventListener {
    public static final String DEFAULT_REGISTRY_HOST = "localhost";
    public static final int DEFAULT_REGISTRY_PORT = 1099;

    private Lobby remoteLobby;
    private Game remoteGame;
    private PlayerClientImpl exportedPlayerCallback;
    private String localPlayerName;
    private final List<GameEventListener> registeredListeners = new ArrayList<>();

    // Creates an empty controller.
    public GameControllerImpl() {}

    // Connects to the registry and prepares the callback stub.
    @Override
    public void connect(String host, int port, String serviceName, String playerName) throws Exception {
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("Player name cannot be blank.");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("Service name cannot be blank.");
        }

        this.localPlayerName = playerName;
        try {
            // Export the callback object before joining the distributed system.
            this.exportedPlayerCallback = new PlayerClientImpl(this);

            // Resolve the remote lobby from the RMI registry.
            Registry registry = LocateRegistry.getRegistry(host, port);
            this.remoteLobby = (Lobby) registry.lookup(serviceName);
        } catch (Exception exception) {
            cleanupExportedCallback();
            this.localPlayerName = null;
            throw exception;
        }
    }

    // Creates a new game through the remote lobby.
    @Override
    public void createGame(String gameName) throws Exception {
        remoteGame = requireLobbyConnection().createGame(gameName, localPlayerName, exportedPlayerCallback);
    }

    // Joins an existing game through the remote lobby.
    @Override
    public void joinGame(String gameName) throws Exception {
        remoteGame = requireLobbyConnection().joinGame(gameName, localPlayerName, exportedPlayerCallback);
    }

    // Retrieves the list of open rooms.
    @Override
    public List<String> getWaitingGames() throws Exception {
        return requireLobbyConnection().getWaitingGames();
    }

    // Sends a move to the active remote game.
    @Override
    public void makeMove(int row, int col) throws Exception {
        if (remoteGame == null) {
            throw new IllegalStateException("No active game room.");
        }
        remoteGame.makeMove(localPlayerName, row, col);
    }

    // Leaves the current remote game if present.
    @Override
    public void leaveGame() throws Exception {
        if (remoteGame != null) {
            try {
                remoteGame.leaveGame(localPlayerName);
            } finally {
                remoteGame = null;
            }
        }
    }

    // Registers one local UI listener.
    @Override
    public void registerEventListener(GameEventListener listener) {
        synchronized (registeredListeners) {
            registeredListeners.add(listener);
        }
    }

    // Disconnects from the distributed session and cleans local exports.
    @Override
    public void disconnect() {
        try {
            leaveGame();
        } catch (Exception exception) {
            // Ignore failure on disconnection cleanup.
        }
        cleanupExportedCallback();
        remoteLobby = null;
    }

    // Returns the local player name.
    @Override
    public String getPlayerName() {
        return localPlayerName;
    }

    // Forwards the game-start callback to local listeners.
    @Override
    public void onGameStarted(BoardState initialState) {
        notifyRegisteredListeners(listener -> listener.onGameStarted(initialState));
    }

    // Forwards the board-update callback to local listeners.
    @Override
    public void onGameUpdated(BoardState updatedState) {
        notifyRegisteredListeners(listener -> listener.onGameUpdated(updatedState));
    }

    // Forwards the opponent-left callback to local listeners.
    @Override
    public void onOpponentLeft(String opponentName) {
        notifyRegisteredListeners(listener -> listener.onOpponentLeft(opponentName));
    }

    // Unexports the client callback stub if it exists.
    private void cleanupExportedCallback() {
        if (exportedPlayerCallback != null) {
            try {
                UnicastRemoteObject.unexportObject(exportedPlayerCallback, true);
            } catch (Exception exception) {
                // Ignore cleanup failures.
            }
            exportedPlayerCallback = null;
        }
    }

    // Returns the remote lobby or fails fast if disconnected.
    private Lobby requireLobbyConnection() {
        if (remoteLobby == null) {
            throw new IllegalStateException("Lobby connection is not established.");
        }
        return remoteLobby;
    }

    // Runs one action for each registered listener.
    private void notifyRegisteredListeners(java.util.function.Consumer<GameEventListener> listenerAction) {
        synchronized (registeredListeners) {
            for (GameEventListener registeredListener : registeredListeners) {
                listenerAction.accept(registeredListener);
            }
        }
    }
}
