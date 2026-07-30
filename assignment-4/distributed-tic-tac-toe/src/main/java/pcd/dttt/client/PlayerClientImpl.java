package pcd.dttt.client;

import java.io.Serial;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.PlayerClient;

/**
 * Implementation of the PlayerClient remote callback interface.
 * Receives remote calls from the server and forwards them to a local listener.
 */
public class PlayerClientImpl extends UnicastRemoteObject implements PlayerClient {
    @Serial
    private static final long serialVersionUID = 1L;
    
    /** The local listener interface to receive callbacks forwarded from the server. */
    private final GameEventListener listener;

    /**
     * Constructs a PlayerClient callback object.
     * Must be exported (done automatically via extending UnicastRemoteObject).
     *
     * @param listener the local listener to receive callbacks
     * @throws RemoteException if an RMI error occurs during export
     */
    public PlayerClientImpl(GameEventListener listener) throws RemoteException {
        super(0); // Export on anonymous port
        this.listener = listener;
    }

    /**
     * {@inheritDoc}
     *
     * @param initialState the initial board state snapshot when the game starts
     * @throws RemoteException if a remote communication error occurs
     */
    @Override
    public void gameStarted(BoardState initialState) throws RemoteException {
        listener.onGameStarted(initialState);
    }

    /**
     * {@inheritDoc}
     *
     * @param newState the updated board state snapshot
     * @throws RemoteException if a remote communication error occurs
     */
    @Override
    public void gameUpdated(BoardState newState) throws RemoteException {
        listener.onGameUpdated(newState);
    }

    /**
     * {@inheritDoc}
     *
     * @param opponentName the nickname of the opponent who left
     * @throws RemoteException if a remote communication error occurs
     */
    @Override
    public void opponentLeft(String opponentName) throws RemoteException {
        listener.onOpponentLeft(opponentName);
    }
}
