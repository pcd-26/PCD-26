package pcd.dttt.common;

import java.rmi.Remote;
import java.rmi.RemoteException;

// Remote callback implemented by each client.
public interface PlayerClient extends Remote {
    // Notifies the client that the match has started.
    void gameStarted(BoardState initialState) throws RemoteException;

    // Sends a fresh board snapshot to the client.
    void gameUpdated(BoardState updatedState) throws RemoteException;

    // Notifies the client that the opponent left.
    void opponentLeft(String opponentName) throws RemoteException;
}
