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
    private Lobby lobby;
    private Game currentGame;
    private PlayerClientImpl clientStub;
    private String playerName;
    private final List<GameEventListener> listeners = new ArrayList<>();

    @Override
    public void connect(String host, int port, String playerName) throws Exception {
        this.playerName = playerName;
        
        // Export our client callback object on an anonymous port
        this.clientStub = new PlayerClientImpl(this);
        
        // Lookup matchmaking Lobby
        Registry registry = LocateRegistry.getRegistry(host, port);
        this.lobby = (Lobby) registry.lookup("Lobby");
    }

    @Override
    public void createGame(String gameName) throws Exception {
        if (lobby == null) {
            throw new IllegalStateException("Lobby connection is not established.");
        }
        this.currentGame = lobby.createGame(gameName, playerName, clientStub);
    }

    @Override
    public void joinGame(String gameName) throws Exception {
        if (lobby == null) {
            throw new IllegalStateException("Lobby connection is not established.");
        }
        this.currentGame = lobby.joinGame(gameName, playerName, clientStub);
    }

    @Override
    public List<String> getWaitingGames() throws Exception {
        if (lobby == null) {
            throw new IllegalStateException("Lobby connection is not established.");
        }
        return lobby.getWaitingGames();
    }

    @Override
    public void makeMove(int row, int col) throws Exception {
        if (currentGame == null) {
            throw new IllegalStateException("No active game room.");
        }
        currentGame.makeMove(playerName, row, col);
    }

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

    @Override
    public void registerEventListener(GameEventListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    @Override
    public void disconnect() {
        try {
            leaveGame();
        } catch (Exception e) {
            // Ignore failure on disconnection cleanup
        }
        if (clientStub != null) {
            try {
                UnicastRemoteObject.unexportObject(clientStub, true);
            } catch (Exception e) {
                // Ignore
            }
            clientStub = null;
        }
        lobby = null;
    }

    @Override
    public String getPlayerName() {
        return playerName;
    }

    // --- GameEventListener RMI Callback Forwarders ---

    @Override
    public void onGameStarted(BoardState initialState) {
        synchronized (listeners) {
            for (GameEventListener l : listeners) {
                l.onGameStarted(initialState);
            }
        }
    }

    @Override
    public void onGameUpdated(BoardState newState) {
        synchronized (listeners) {
            for (GameEventListener l : listeners) {
                l.onGameUpdated(newState);
            }
        }
    }

    @Override
    public void onOpponentLeft(String opponentName) {
        synchronized (listeners) {
            for (GameEventListener l : listeners) {
                l.onOpponentLeft(opponentName);
            }
        }
    }
}
