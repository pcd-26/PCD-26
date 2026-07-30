package pcd.dttt.server;

import static org.junit.jupiter.api.Assertions.*;

import java.rmi.RemoteException;
import java.rmi.NoSuchObjectException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pcd.dttt.common.Game;
import pcd.dttt.common.PlayerClient;
import pcd.dttt.common.exceptions.GameAlreadyExistsException;
import pcd.dttt.common.exceptions.GameFullException;
import pcd.dttt.common.exceptions.GameNotFoundException;
import pcd.dttt.common.BoardState;

public class LobbyImplTest {

    private LobbyImpl lobby;
    private PlayerClient dummyClient1;
    private PlayerClient dummyClient2;

    @BeforeEach
    public void setUp() throws Exception {
        lobby = new LobbyImpl();
        dummyClient1 = new DummyPlayerClient();
        dummyClient2 = new DummyPlayerClient();
    }

    @AfterEach
    public void tearDown() {
        if (lobby != null) {
            lobby.close();
        }
    }

    @Test
    public void testCreateGame() throws Exception {
        Game game = lobby.createGame("Room1", "PlayerX", dummyClient1);
        assertNotNull(game);
        assertEquals("Room1", game.getName());

        List<String> waiting = lobby.getWaitingGames();
        assertEquals(1, waiting.size());
        assertTrue(waiting.contains("Room1"));
    }

    @Test
    public void testCreateGameAlreadyExists() throws Exception {
        lobby.createGame("Room1", "PlayerX", dummyClient1);

        assertThrows(GameAlreadyExistsException.class, () -> {
            lobby.createGame("Room1", "PlayerO", dummyClient2);
        });
    }

    @Test
    public void testJoinGame() throws Exception {
        lobby.createGame("Room1", "PlayerX", dummyClient1);

        Game game = lobby.joinGame("Room1", "PlayerO", dummyClient2);
        assertNotNull(game);

        // After joining, it should not be in the waiting games list anymore
        List<String> waiting = lobby.getWaitingGames();
        assertTrue(waiting.isEmpty());
    }

    @Test
    public void testDuplicateJoinRejectedClearly() throws Exception {
        lobby.createGame("Room1", "PlayerX", dummyClient1);
        lobby.joinGame("Room1", "PlayerO", dummyClient2);

        assertThrows(GameFullException.class, () -> {
            lobby.joinGame("Room1", "PlayerThird", new DummyPlayerClient());
        });
    }

    @Test
    public void testJoinNonExistentGame() {
        assertThrows(GameNotFoundException.class, () -> {
            lobby.joinGame("GhostRoom", "PlayerO", dummyClient2);
        });
    }

    @Test
    public void testJoinAlreadyStartedGame() throws Exception {
        lobby.createGame("Room1", "PlayerX", dummyClient1);
        lobby.joinGame("Room1", "PlayerO", dummyClient2);

        PlayerClient thirdClient = new DummyPlayerClient();
        assertThrows(GameFullException.class, () -> {
            lobby.joinGame("Room1", "PlayerThird", thirdClient);
        });
    }

    @Test
    public void testPruningCompletedGames() throws Exception {
        lobby.createGame("Room1", "PlayerX", dummyClient1);
        Game game = lobby.joinGame("Room1", "PlayerO", dummyClient2);

        // Complete the game (make moves until win or abandon)
        game.leaveGame("PlayerX"); // Abandons

        // Requesting waiting games should prune Room1 from the map
        List<String> waiting = lobby.getWaitingGames();
        assertTrue(waiting.isEmpty());

        // Attempting to join should now fail because it's been pruned from active games
        assertThrows(GameNotFoundException.class, () -> {
            lobby.joinGame("Room1", "AnotherPlayer", dummyClient2);
        });
    }

    @Test
    public void testConcurrentCreateWithSameName() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Exception> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successes = new AtomicInteger();

        Runnable createTask = () -> {
            ready.countDown();
            try {
                start.await();
                lobby.createGame("SharedRoom", "Player-" + Thread.currentThread().getId(), new DummyPlayerClient());
                successes.incrementAndGet();
            } catch (Exception e) {
                failures.add(e);
            }
        };

        executor.submit(createTask);
        executor.submit(createTask);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, successes.get());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof GameAlreadyExistsException);
    }

    @Test
    public void testConcurrentJoinsAllowOnlyOneOpponent() throws Exception {
        lobby.createGame("Room1", "PlayerX", dummyClient1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Exception> failures = Collections.synchronizedList(new ArrayList<>());

        Runnable joinTaskA = () -> {
            ready.countDown();
            try {
                start.await();
                lobby.joinGame("Room1", "PlayerO", new DummyPlayerClient());
                successes.incrementAndGet();
            } catch (Exception e) {
                failures.add(e);
            }
        };

        Runnable joinTaskB = () -> {
            ready.countDown();
            try {
                start.await();
                lobby.joinGame("Room1", "PlayerThird", new DummyPlayerClient());
                successes.incrementAndGet();
            } catch (Exception e) {
                failures.add(e);
            }
        };

        executor.submit(joinTaskA);
        executor.submit(joinTaskB);
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, successes.get());
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof GameFullException);
    }

    @Test
    public void testLobbyCloseUnexportsRemoteObject() throws Exception {
        lobby.createGame("Room1", "PlayerX", dummyClient1);
        lobby.close();

        assertThrows(NoSuchObjectException.class, () -> UnicastRemoteObject.unexportObject(lobby, false));
    }

    private static class DummyPlayerClient implements PlayerClient {
        @Override
        public void gameStarted(BoardState initialState) throws RemoteException {}
        @Override
        public void gameUpdated(BoardState newState) throws RemoteException {}
        @Override
        public void opponentLeft(String opponentName) throws RemoteException {}
    }
}
