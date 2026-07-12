package pcd.dttt.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import pcd.dttt.common.exceptions.GameAlreadyExistsException;
import pcd.dttt.common.exceptions.GameFullException;
import pcd.dttt.common.exceptions.GameNotFoundException;

/**
 * RMI Remote interface representing the central lobby.
 * Bound in the RMI Registry under a known name.
 */
public interface Lobby extends Remote {
    /** Default RMI binding name used by the distributed TTT service. */
    String DEFAULT_BINDING_NAME = "Lobby";

    /**
     * Creates a new game.
     *
     * @param gameName the unique name of the game
     * @param playerName the name of the player creating the game
     * @param client the player's client callback reference
     * @return the Game remote reference
     * @throws RemoteException if an RMI error occurs
     * @throws GameAlreadyExistsException if a game with that name already exists
     */
    Game createGame(String gameName, String playerName, PlayerClient client) 
        throws RemoteException, GameAlreadyExistsException;

    /**
     * Joins an existing game.
     *
     * @param gameName the name of the game to join
     * @param playerName the name of the player joining the game
     * @param client the player's client callback reference
     * @return the Game remote reference
     * @throws RemoteException if an RMI error occurs
     * @throws GameNotFoundException if the game does not exist
     * @throws GameFullException if the game already has two players
     */
    Game joinGame(String gameName, String playerName, PlayerClient client) 
        throws RemoteException, GameNotFoundException, GameFullException;

    /**
     * Gets a list of names of games that are waiting for an opponent.
     */
    List<String> getWaitingGames() throws RemoteException;
}
