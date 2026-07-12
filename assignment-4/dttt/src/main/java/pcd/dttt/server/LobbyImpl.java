package pcd.dttt.server;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Iterator;
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
 *   <li>Uses a {@link ConcurrentHashMap} to store active games, but public methods are marked
 *       {@code synchronized} to ensure atomic operations during room check-and-create or join sequences.</li>
 *   <li>Prunes terminal rooms (won, drawn, or abandoned) from memory during every list or join lookup
 *       to keep memory utilization bounded without requiring a background sweeper thread.</li>
 * </ul>
 */
public class LobbyImpl extends UnicastRemoteObject implements Lobby {
    private static final long serialVersionUID = 1L;

    /** Map of active game rooms keyed by their unique game name. */
    private final Map<String, GameImpl> games = new ConcurrentHashMap<>();

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
    public synchronized Game createGame(String gameName, String playerName, PlayerClient client) 
            throws RemoteException, GameAlreadyExistsException {
        
        pruneFinishedGames();
        
        if (games.containsKey(gameName)) {
            throw new GameAlreadyExistsException("A game with the name '" + gameName + "' already exists.");
        }

        GameImpl game = new GameImpl(gameName, playerName, client);
        games.put(gameName, game);
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
    public synchronized Game joinGame(String gameName, String playerName, PlayerClient client) 
            throws RemoteException, GameNotFoundException, GameFullException {
        
        pruneFinishedGames();
        
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
    public synchronized List<String> getWaitingGames() throws RemoteException {
        List<String> waiting = new ArrayList<>();
        Iterator<Map.Entry<String, GameImpl>> it = games.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, GameImpl> entry = it.next();
            GameImpl game = entry.getValue();
            GameStatus status = game.getBoardState().getStatus();
            if (status == GameStatus.WAITING) {
                waiting.add(entry.getKey());
            } else if (status != GameStatus.ACTIVE) {
                // Prune completed/abandoned games from the active list
                it.remove();
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
    private void pruneFinishedGames() throws RemoteException {
        Iterator<Map.Entry<String, GameImpl>> it = games.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, GameImpl> entry = it.next();
            GameStatus status = entry.getValue().getBoardState().getStatus();
            if (status != GameStatus.WAITING && status != GameStatus.ACTIVE) {
                it.remove();
            }
        }
    }
}
