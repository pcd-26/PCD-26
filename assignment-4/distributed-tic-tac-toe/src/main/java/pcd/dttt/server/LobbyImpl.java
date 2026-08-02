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

/**
 * Implementation of the {@link Lobby} RMI remote interface.
 * Manages the list of active games, matchmaking, and clean up.
 * 
 * <p><strong>Thread Safety:</strong></p>
 * <ul>
 *   <li>Uses a {@link ConcurrentHashMap} to store active games and atomic map operations to protect
 *       room creation from duplicate names without serializing the whole lobby.</li>
 *   <li>Delegates match-level contention to each {@link GameImpl} instance so concurrent joins for
 *       different rooms do not block one another.</li>
 *   <li>Prunes terminal rooms (won, drawn, or abandoned) from memory during every list or join lookup
 *       to keep memory utilization bounded without requiring a background sweeper thread.</li>
 * </ul>
 */
public class LobbyImpl extends UnicastRemoteObject implements Lobby {
    private static final long serialVersionUID = 1L;

    /** Map of active game rooms keyed by their unique game name. */
    private final Map<String, GameImpl> games = new ConcurrentHashMap<>();

    /** True once the lobby has been explicitly closed. */
    private volatile boolean closed;

    /**
     * Constructs a new Lobby remote instance and exports it on an anonymous port.
     *
     * @throws RemoteException if an RMI error occurs during export
     */
    public LobbyImpl() throws RemoteException {
        super(0); // Export on anonymous port
    }

    /**
     * Creates a new game room with a unique name.
     * Prunes inactive games first, verifies name uniqueness, instantiates `GameImpl`,
     * and maps it in the lobby.
     *
     * @param gameName the unique name of the game room to create
     * @param playerName the nickname of Player X (the creator)
     * @param client the client RMI callback stub of Player X
     * @return the remote reference to the created Game
     * @throws RemoteException if an RMI error occurs
     * @throws GameAlreadyExistsException if a game with the requested name is already registered
     */
    @Override
    public Game createGame(String gameName, String playerName, PlayerClient client)
            throws RemoteException, GameAlreadyExistsException {
        ensureOpen();
        pruneInactiveGames();

        GameImpl game = new GameImpl(gameName, playerName, client);
        GameImpl existing = games.putIfAbsent(gameName, game);
        if (existing != null) {
            game.close();
            throw new GameAlreadyExistsException("A game with the name '" + gameName + "' already exists.");
        }
        System.out.println("Game created: '" + gameName + "' by player: " + playerName);
        return game;
    }

    /**
     * Joins an existing game room as the opponent (Player O).
     * Prunes inactive games, retrieves the room from mapping, and registers the joining client.
     *
     * @param gameName the name of the game room to join
     * @param playerName the nickname of Player O (the joiner)
     * @param client the client RMI callback stub of Player O
     * @return the remote reference to the joined Game
     * @throws RemoteException if an RMI error occurs
     * @throws GameNotFoundException if no game room exists with the given name
     * @throws GameFullException if the game is already in progress or has finished
     */
    @Override
    public Game joinGame(String gameName, String playerName, PlayerClient client)
            throws RemoteException, GameNotFoundException, GameFullException {
        ensureOpen();
        pruneInactiveGames();
        
        GameImpl game = games.get(gameName);
        if (game == null) {
            throw new GameNotFoundException("Game '" + gameName + "' not found.");
        }

        game.join(playerName, client);
        System.out.println("Player '" + playerName + "' joined game: '" + gameName + "'");
        return game;
    }

    /**
     * Retrieves a list of names of all games currently in the {@link GameStatus#WAITING} state.
     * Concurrently prunes completed or abandoned matches from the active map.
     *
     * @return a List of names of waiting games
     * @throws RemoteException if an RMI error occurs
     */
    @Override
    public List<String> getWaitingGames() throws RemoteException {
        ensureOpen();
        List<String> waiting = new ArrayList<>();
        for (Map.Entry<String, GameImpl> entry : snapshotGames()) {
            GameStatus status = entry.getValue().getBoardState().status();
            if (status.isWaiting()) {
                waiting.add(entry.getKey());
            } else if (status.isTerminal()) {
                removeGame(entry.getKey(), entry.getValue());
            }
        }
        return waiting;
    }

    /**
     * Iterates over all active games and removes any that have finished (WON, DRAW, ABANDONED).
     * Assumes synchronized lock on {@code this} is held.
     *
     * @throws RemoteException if an RMI error occurs while querying game states
     */
    private void pruneInactiveGames() throws RemoteException {
        for (Map.Entry<String, GameImpl> entry : snapshotGames()) {
            if (entry.getValue().getBoardState().status().isTerminal()) {
                removeGame(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Returns a stable snapshot of the lobby games to iterate without concurrent modification. */
    private List<Map.Entry<String, GameImpl>> snapshotGames() {
        return new ArrayList<>(games.entrySet());
    }

    /** Closes a game and removes it from the lobby if it is still mapped to the expected instance. */
    private void removeGame(String gameName, GameImpl game) {
        game.close();
        games.remove(gameName, game);
    }

    /**
     * Closes the lobby and every game it still owns.
     * This is used for test cleanup and server shutdown hooks.
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (GameImpl game : games.values()) {
            game.close();
        }
        games.clear();
        try {
            UnicastRemoteObject.unexportObject(this, true);
        } catch (Exception e) {
            // Ignore cleanup failures.
        }
    }

    /**
     * Fails fast if the lobby has been closed.
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Lobby has been closed.");
        }
    }
}
