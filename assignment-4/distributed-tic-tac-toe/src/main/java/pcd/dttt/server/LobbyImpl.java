package pcd.dttt.server;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import pcd.dttt.common.Game;
import pcd.dttt.common.GameStatus;
import pcd.dttt.common.Lobby;
import pcd.dttt.common.PlayerClient;
import pcd.dttt.common.exceptions.GameAlreadyExistsException;
import pcd.dttt.common.exceptions.GameFullException;
import pcd.dttt.common.exceptions.GameNotFoundException;

// Server-side lobby that creates, finds, and prunes rooms.
public class LobbyImpl extends UnicastRemoteObject implements Lobby {
    private static final long serialVersionUID = 1L;

    private final Map<String, GameImpl> activeGamesByName = new ConcurrentHashMap<>();
    private volatile boolean isClosed;

    // Exports the lobby on an anonymous RMI port.
    public LobbyImpl() throws RemoteException {
        super(0);
    }

    // Creates a new room if its name is still available.
    @Override
    public Game createGame(String gameName, String playerName, PlayerClient playerClient)
            throws RemoteException, GameAlreadyExistsException {
        ensureOpen();
        pruneFinishedGames();

        // Build the room first, then publish it atomically in the map.
        GameImpl createdGame = new GameImpl(gameName, playerName, playerClient);
        GameImpl existingGame = activeGamesByName.putIfAbsent(gameName, createdGame);
        if (existingGame != null) {
            createdGame.close();
            throw new GameAlreadyExistsException("A game with the name '" + gameName + "' already exists.");
        }
        System.out.println("Game created: '" + gameName + "' by player: " + playerName);
        return createdGame;
    }

    // Joins an existing room as player O.
    @Override
    public Game joinGame(String gameName, String playerName, PlayerClient playerClient)
            throws RemoteException, GameNotFoundException, GameFullException {
        ensureOpen();
        pruneFinishedGames();

        GameImpl requestedGame = activeGamesByName.get(gameName);
        if (requestedGame == null) {
            throw new GameNotFoundException("Game '" + gameName + "' not found.");
        }

        requestedGame.joinSecondPlayer(playerName, playerClient);
        System.out.println("Player '" + playerName + "' joined game: '" + gameName + "'");
        return requestedGame;
    }

    // Lists the rooms that are still waiting for a second player.
    @Override
    public List<String> getWaitingGames() throws RemoteException {
        ensureOpen();
        List<String> waitingGameNames = new ArrayList<>();
        for (Map.Entry<String, GameImpl> activeGameEntry : snapshotActiveGames()) {
            GameStatus gameStatus = activeGameEntry.getValue().getBoardState().status();
            if (gameStatus.isWaiting()) {
                waitingGameNames.add(activeGameEntry.getKey());
            } else if (gameStatus.isTerminal()) {
                closeAndRemoveGame(activeGameEntry.getKey(), activeGameEntry.getValue());
            }
        }
        return waitingGameNames;
    }

    // Removes terminal rooms from the lobby map.
    private void pruneFinishedGames() throws RemoteException {
        for (Map.Entry<String, GameImpl> activeGameEntry : snapshotActiveGames()) {
            if (activeGameEntry.getValue().getBoardState().status().isTerminal()) {
                closeAndRemoveGame(activeGameEntry.getKey(), activeGameEntry.getValue());
            }
        }
    }

    // Copies the current map entries for safe iteration.
    private List<Map.Entry<String, GameImpl>> snapshotActiveGames() {
        return new ArrayList<>(activeGamesByName.entrySet());
    }

    // Closes one room and removes it only if the mapping is unchanged.
    private void closeAndRemoveGame(String gameName, GameImpl gameInstance) {
        gameInstance.close();
        activeGamesByName.remove(gameName, gameInstance);
    }

    // Closes the lobby and all remaining rooms.
    public synchronized void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        for (GameImpl gameInstance : activeGamesByName.values()) {
            gameInstance.close();
        }
        activeGamesByName.clear();
        try {
            UnicastRemoteObject.unexportObject(this, true);
        } catch (Exception exception) {
            // Ignore cleanup failures.
        }
    }

    // Fails fast if the lobby is already closed.
    private void ensureOpen() {
        if (isClosed) {
            throw new IllegalStateException("Lobby has been closed.");
        }
    }
}
