package pcd.dttt.client;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.PlayerClient;

/**
 * Implementation of the PlayerClient remote callback interface.
 * Receives remote calls from the server and forwards them to a local listener.
 */
public class PlayerClientImpl extends UnicastRemoteObject implements PlayerClient {
    private static final long serialVersionUID = 1L;
    
    private final GameEventListener listener;

    /**
     * Constructs a PlayerClient callback object.
     * Must be exported (done automatically via extending UnicastRemoteObject).
     */
    public PlayerClientImpl(GameEventListener listener) throws RemoteException {
        super(0); // Export on anonymous port
        this.listener = listener;
    }

    @Override
    public void gameStarted(BoardState initialState) throws RemoteException {
        listener.onGameStarted(initialState);
    }

    @Override
    public void gameUpdated(BoardState newState) throws RemoteException {
        listener.onGameUpdated(newState);
    }

    @Override
    public void opponentLeft(String opponentName) throws RemoteException {
        listener.onOpponentLeft(opponentName);
    }
}
