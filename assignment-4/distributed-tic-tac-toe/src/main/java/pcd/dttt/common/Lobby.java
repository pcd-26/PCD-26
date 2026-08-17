package pcd.dttt.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import pcd.dttt.common.exceptions.GameAlreadyExistsException;
import pcd.dttt.common.exceptions.GameFullException;
import pcd.dttt.common.exceptions.GameNotFoundException;

// Remote entry point used to create, join, and list games.
public interface Lobby extends Remote {
    String DEFAULT_BINDING_NAME = "Lobby";

    // Creates a new waiting game and returns its remote stub.
    Game createGame(String gameName, String playerName, PlayerClient playerClient)
        throws RemoteException, GameAlreadyExistsException;

    // Joins an existing waiting game and returns its remote stub.
    Game joinGame(String gameName, String playerName, PlayerClient playerClient)
        throws RemoteException, GameNotFoundException, GameFullException;

    // Returns the names of games still waiting for a second player.
    List<String> getWaitingGames() throws RemoteException;
}
