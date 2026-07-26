package pcd.dttt.common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * RMI Remote interface for the client. The server calls these callback methods
 * to notify the client of game events asynchronously.
 */
public interface PlayerClient extends Remote {
    /**
     * Called when the game starts (i.e., when both players have joined).
     *
     * @param initialState the initial board state snapshot when the game starts
     * @throws RemoteException if an RMI communication error occurs
     */
    void gameStarted(BoardState initialState) throws RemoteException;

    /**
     * Called when the game state updates (e.g., after a move is made).
     *
     * @param newState the updated board state snapshot
     * @throws RemoteException if an RMI communication error occurs
     */
    void gameUpdated(BoardState newState) throws RemoteException;

    /**
     * Called when the opponent has explicitly left the game or disconnected.
     *
     * @param opponentName the nickname of the opponent who left
     * @throws RemoteException if an RMI communication error occurs
     */
    void opponentLeft(String opponentName) throws RemoteException;
}
