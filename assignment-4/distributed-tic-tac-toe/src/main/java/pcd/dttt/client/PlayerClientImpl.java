package pcd.dttt.client;

import java.io.Serial;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.PlayerClient;

// Forwards remote callbacks to a local listener.
public class PlayerClientImpl extends UnicastRemoteObject implements PlayerClient {
    @Serial
    private static final long serialVersionUID = 1L;
    
    private final GameEventListener localEventListener;

    // Exports the callback object on creation.
    public PlayerClientImpl(GameEventListener localEventListener) throws RemoteException {
        super(0);
        this.localEventListener = localEventListener;
    }

    // Forwards the game-start event locally.
    @Override
    public void gameStarted(BoardState initialState) throws RemoteException {
        localEventListener.onGameStarted(initialState);
    }

    // Forwards the board-update event locally.
    @Override
    public void gameUpdated(BoardState updatedState) throws RemoteException {
        localEventListener.onGameUpdated(updatedState);
    }

    // Forwards the opponent-left event locally.
    @Override
    public void opponentLeft(String opponentName) throws RemoteException {
        localEventListener.onOpponentLeft(opponentName);
    }
}
