package pcd.dttt.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.Game;
import pcd.dttt.common.GameStatus;
import pcd.dttt.common.Lobby;
import pcd.dttt.common.PlayerClient;
import pcd.dttt.server.LobbyImpl;

/**
 * End-to-end RMI integration tests that use an isolated local registry.
 */
public class DtttRmiIntegrationTest {
    private Registry registry;
    private LobbyImpl lobbyImpl;
    private int registryPort;

    @BeforeEach
    public void setUp() throws Exception {
        registryPort = findFreePort();
        registry = LocateRegistry.createRegistry(registryPort);
        lobbyImpl = new LobbyImpl();
        registry.rebind(Lobby.DEFAULT_BINDING_NAME, lobbyImpl);
    }

    @AfterEach
    public void tearDown() {
        if (lobbyImpl != null) {
            lobbyImpl.close();
        }
        if (registry != null) {
            try {
                UnicastRemoteObject.unexportObject(registry, true);
            } catch (NoSuchObjectException e) {
                // Already unexported.
            }
        }
    }

    @Test
    public void testRemoteGameLifecycleThroughRegistry() throws Exception {
        Lobby remoteLobby = (Lobby) registry.lookup(Lobby.DEFAULT_BINDING_NAME);
        TestPlayerClient creator = new TestPlayerClient();
        TestPlayerClient opponent = new TestPlayerClient();
        try {
            Game game = remoteLobby.createGame("IntegrationRoom", "Alice", creator);
            assertEquals("IntegrationRoom", game.getName());

            remoteLobby.joinGame("IntegrationRoom", "Bob", opponent);

            assertTrue(creator.started.await(5, TimeUnit.SECONDS));
            assertTrue(opponent.started.await(5, TimeUnit.SECONDS));

            BoardState initialState = game.getBoardState();
            assertEquals(GameStatus.ACTIVE, initialState.status());
            assertEquals("Alice", initialState.turnOf());

            game.makeMove("Alice", 0, 0);

            assertTrue(creator.updated.await(5, TimeUnit.SECONDS));
            assertTrue(opponent.updated.await(5, TimeUnit.SECONDS));

            BoardState afterMove = game.getBoardState();
            assertEquals('X', afterMove.getMark(0, 0));
            assertEquals("Bob", afterMove.turnOf());

            game.leaveGame("Alice");
            assertTrue(opponent.left.await(5, TimeUnit.SECONDS));
            assertEquals(GameStatus.ABANDONED, game.getBoardState().status());
        } finally {
            creator.close();
            opponent.close();
        }
    }

    @Test
    public void testRegistryBindingIsReachableByName() throws Exception {
        Lobby remoteLobby = (Lobby) registry.lookup(Lobby.DEFAULT_BINDING_NAME);
        assertNotNull(remoteLobby);
        TestPlayerClient client = new TestPlayerClient();
        try {
            Game game = remoteLobby.createGame("LookupRoom", "Alice", client);
            assertEquals("LookupRoom", game.getName());
        } finally {
            client.close();
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static class TestPlayerClient extends UnicastRemoteObject implements PlayerClient {
        private static final long serialVersionUID = 1L;

        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch updated = new CountDownLatch(1);
        final CountDownLatch left = new CountDownLatch(1);

        TestPlayerClient() throws RemoteException {
            super(0);
        }

        void close() {
            try {
                UnicastRemoteObject.unexportObject(this, true);
            } catch (NoSuchObjectException e) {
                // Already closed.
            }
        }

        @Override
        public void gameStarted(BoardState initialState) throws RemoteException {
            started.countDown();
        }

        @Override
        public void gameUpdated(BoardState newState) throws RemoteException {
            updated.countDown();
        }

        @Override
        public void opponentLeft(String opponentName) throws RemoteException {
            left.countDown();
        }
    }
}
