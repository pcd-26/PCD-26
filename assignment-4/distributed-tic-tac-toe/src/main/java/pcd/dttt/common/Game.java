package pcd.dttt.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import pcd.dttt.common.exceptions.InvalidMoveException;
import pcd.dttt.common.exceptions.NotYourTurnException;

// Remote controller for a single match.
public interface Game extends Remote {
    // Returns the room name.
    String getName() throws RemoteException;

    // Applies a move on behalf of a player.
    void makeMove(String playerName, int row, int col)
        throws RemoteException, NotYourTurnException, InvalidMoveException;

    // Leaves the current match and abandons it.
    void leaveGame(String playerName) throws RemoteException;

    // Returns the latest immutable board snapshot.
    BoardState getBoardState() throws RemoteException;
}
