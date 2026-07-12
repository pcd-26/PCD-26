package pcd.dttt.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import pcd.dttt.common.exceptions.InvalidMoveException;
import pcd.dttt.common.exceptions.NotYourTurnException;

/**
 * RMI Remote interface representing an ongoing Tic-Tac-Toe match.
 */
public interface Game extends Remote {
    /**
     * Gets the name of the game.
     */
    String getName() throws RemoteException;

    /**
     * Attempts to place a mark on the board.
     *
     * @param playerName the name of the player making the move
     * @param row zero-indexed row (0, 1, or 2)
     * @param col zero-indexed column (0, 1, or 2)
     * @throws RemoteException if an RMI error occurs
     * @throws NotYourTurnException if it is not this player's turn
     * @throws InvalidMoveException if the coordinates are out of bounds, cell is occupied, or game is not active
     */
    void makeMove(String playerName, int row, int col) 
        throws RemoteException, NotYourTurnException, InvalidMoveException;

    /**
     * Explicitly leaves the game, causing the game to be abandoned.
     *
     * @param playerName the name of the player leaving
     * @throws RemoteException if an RMI error occurs
     */
    void leaveGame(String playerName) throws RemoteException;

    /**
     * Gets the current board state snapshot.
     */
    BoardState getBoardState() throws RemoteException;
}
