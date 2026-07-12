package pcd.dttt.server;

import static org.junit.jupiter.api.Assertions.*;

import java.rmi.RemoteException;
import java.rmi.NoSuchObjectException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.GameStatus;
import pcd.dttt.common.PlayerClient;
import pcd.dttt.common.exceptions.InvalidMoveException;
import pcd.dttt.common.exceptions.NotYourTurnException;

public class GameImplTest {

    private TestPlayerClient clientX;
    private TestPlayerClient clientO;
    private GameImpl game;

    @BeforeEach
    public void setUp() throws Exception {
        clientX = new TestPlayerClient();
        clientO = new TestPlayerClient();
        game = new GameImpl("TestRoom", "PlayerX", clientX);
    }

    @AfterEach
    public void tearDown() {
        if (game != null) {
            game.close();
        }
    }

    @Test
    public void testInitialization() throws Exception {
        BoardState state = game.getBoardState();
        assertEquals("TestRoom", game.getName());
        assertEquals(GameStatus.WAITING, state.getStatus());
        assertEquals("PlayerX", state.getPlayerX());
        assertNull(state.getPlayerO());
        assertNull(state.getTurnOf());
    }

    @Test
    public void testJoinGame() throws Exception {
        game.join("PlayerO", clientO);
        BoardState state = game.getBoardState();

        assertEquals(GameStatus.ACTIVE, state.getStatus());
        assertEquals("PlayerO", state.getPlayerO());
        assertEquals("PlayerX", state.getTurnOf()); // X starts
    }

    @Test
    public void testMoveRejectedBeforeSecondPlayerJoins() {
        assertThrows(InvalidMoveException.class, () -> {
            game.makeMove("PlayerX", 0, 0);
        });
    }

    @Test
    public void testMakeMoveAndTurnSwitch() throws Exception {
        game.join("PlayerO", clientO);
        
        // Player X moves
        game.makeMove("PlayerX", 0, 0);
        BoardState state1 = game.getBoardState();
        assertEquals('X', state1.getMark(0, 0));
        assertEquals("PlayerO", state1.getTurnOf());

        // Player O moves
        game.makeMove("PlayerO", 1, 1);
        BoardState state2 = game.getBoardState();
        assertEquals('O', state2.getMark(1, 1));
        assertEquals("PlayerX", state2.getTurnOf());
    }

    @Test
    public void testNotYourTurnException() throws Exception {
        game.join("PlayerO", clientO);

        // Player O tries to move first (it is X's turn)
        assertThrows(NotYourTurnException.class, () -> {
            game.makeMove("PlayerO", 0, 0);
        });
    }

    @Test
    public void testInvalidMoveOccupied() throws Exception {
        game.join("PlayerO", clientO);

        game.makeMove("PlayerX", 0, 0);

        // Player O tries to play on X's cell (0, 0)
        assertThrows(InvalidMoveException.class, () -> {
            game.makeMove("PlayerO", 0, 0);
        });
    }

    @Test
    public void testInvalidMoveOutOfBounds() throws Exception {
        game.join("PlayerO", clientO);

        assertThrows(InvalidMoveException.class, () -> {
            game.makeMove("PlayerX", -1, 0);
        });

        assertThrows(InvalidMoveException.class, () -> {
            game.makeMove("PlayerX", 3, 0);
        });
    }

    @Test
    public void testWinConditionHorizontal() throws Exception {
        game.join("PlayerO", clientO);

        // X X X in row 0
        // O O   in row 1
        game.makeMove("PlayerX", 0, 0); // X
        game.makeMove("PlayerO", 1, 0); // O
        game.makeMove("PlayerX", 0, 1); // X
        game.makeMove("PlayerO", 1, 1); // O
        game.makeMove("PlayerX", 0, 2); // X (wins)

        BoardState state = game.getBoardState();
        assertEquals(GameStatus.WON_X, state.getStatus());
        assertNull(state.getTurnOf());
    }

    @Test
    public void testWinConditionDiagonal() throws Exception {
        game.join("PlayerO", clientO);

        // X: (0,0), (1,1), (2,2)
        // O: (0,1), (0,2)
        game.makeMove("PlayerX", 0, 0);
        game.makeMove("PlayerO", 0, 1);
        game.makeMove("PlayerX", 1, 1);
        game.makeMove("PlayerO", 0, 2);
        game.makeMove("PlayerX", 2, 2); // X wins

        BoardState state = game.getBoardState();
        assertEquals(GameStatus.WON_X, state.getStatus());
    }

    @Test
    public void testWinConditionVertical() throws Exception {
        game.join("PlayerO", clientO);

        // X wins in column 1
        game.makeMove("PlayerX", 0, 1);
        game.makeMove("PlayerO", 0, 0);
        game.makeMove("PlayerX", 1, 1);
        game.makeMove("PlayerO", 1, 0);
        game.makeMove("PlayerX", 2, 1);

        BoardState state = game.getBoardState();
        assertEquals(GameStatus.WON_X, state.getStatus());
    }

    @Test
    public void testDrawCondition() throws Exception {
        game.join("PlayerO", clientO);

        // X O X
        // X O O
        // O X O
        // (Draw sequence)
        game.makeMove("PlayerX", 0, 0); // X
        game.makeMove("PlayerO", 0, 1); // O
        game.makeMove("PlayerX", 0, 2); // X
        game.makeMove("PlayerO", 1, 1); // O
        game.makeMove("PlayerX", 1, 0); // X
        game.makeMove("PlayerO", 1, 2); // O
        game.makeMove("PlayerX", 2, 1); // X
        game.makeMove("PlayerO", 2, 0); // O
        game.makeMove("PlayerX", 2, 2); // X (draws since board full and no winner)

        BoardState state = game.getBoardState();
        assertEquals(GameStatus.DRAW, state.getStatus());
    }

    @Test
    public void testLeaveGame() throws Exception {
        game.join("PlayerO", clientO);

        game.leaveGame("PlayerX");

        BoardState state = game.getBoardState();
        assertEquals(GameStatus.ABANDONED, state.getStatus());
        assertNull(state.getTurnOf());
    }

    @Test
    public void testBoardSnapshotIsDefensivelyCopied() throws Exception {
        game.join("PlayerO", clientO);
        game.makeMove("PlayerX", 0, 0);

        BoardState snapshot = game.getBoardState();
        char[][] leaked = snapshot.getGrid();
        leaked[0][0] = 'O';

        assertEquals('X', snapshot.getMark(0, 0));
        assertEquals('X', game.getBoardState().getMark(0, 0));
    }

    @Test
    public void testOpenCallPreventsBlocking() throws Exception {
        PlayerClient blockingClient = new PlayerClient() {
            @Override
            public void gameStarted(BoardState initialState) throws RemoteException {}
            
            @Override
            public void gameUpdated(BoardState newState) throws RemoteException {
                try {
                    // Simulate network hang or slow handling
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public void opponentLeft(String opponentName) throws RemoteException {}
        };

        PlayerClient fastClient = new PlayerClient() {
            @Override
            public void gameStarted(BoardState initialState) throws RemoteException {}
            @Override
            public void gameUpdated(BoardState newState) throws RemoteException {}
            @Override
            public void opponentLeft(String opponentName) throws RemoteException {}
        };

        GameImpl gameWithBlocking = new GameImpl("BlockRoom", "PlayerX", blockingClient);
        try {
            gameWithBlocking.join("PlayerO", fastClient);

            long start = System.currentTimeMillis();
            // Player X moves, which triggers the blocking callback.
            // It must NOT block the main thread making the move.
            gameWithBlocking.makeMove("PlayerX", 0, 0);
            long duration = System.currentTimeMillis() - start;

            assertTrue(duration < 1000, "makeMove should return immediately; actual duration: " + duration + "ms");

            start = System.currentTimeMillis();
            // Opponent O should also be able to make their move immediately.
            gameWithBlocking.makeMove("PlayerO", 1, 1);
            duration = System.currentTimeMillis() - start;

            assertTrue(duration < 1000, "Opponent move should not be blocked; actual duration: " + duration + "ms");
        } finally {
            gameWithBlocking.close();
        }
    }

    @Test
    public void testConcurrentMovesSameCell() throws Exception {
        game.join("PlayerO", clientO);
        
        int numThreads = 10;
        java.util.concurrent.atomic.AtomicInteger successXCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failXCount = new java.util.concurrent.atomic.AtomicInteger(0);
        
        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(() -> {
                try {
                    game.makeMove("PlayerX", 0, 0);
                    successXCount.incrementAndGet();
                } catch (Exception e) {
                    failXCount.incrementAndGet();
                }
            });
        }
        
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        
        assertEquals(1, successXCount.get(), "Only exactly one thread should succeed");
        assertEquals(numThreads - 1, failXCount.get(), "All other threads should fail");
    }

    @Test
    public void testCloseUnexportsAndShutsDownExecutor() throws Exception {
        game.join("PlayerO", clientO);
        game.leaveGame("PlayerX");

        game.close();

        assertTrue(game.isCallbackExecutorShutdown(), "Callback executor should be shut down after close()");
        assertThrows(NoSuchObjectException.class, () -> UnicastRemoteObject.unexportObject(game, false));
    }

    // Helper class mimicking client callback behavior
    private static class TestPlayerClient implements PlayerClient {
        final List<BoardState> updates = new ArrayList<>();
        boolean leftNotificationReceived = false;

        @Override
        public void gameStarted(BoardState initialState) throws RemoteException {
            updates.add(initialState);
        }

        @Override
        public void gameUpdated(BoardState newState) throws RemoteException {
            updates.add(newState);
        }

        @Override
        public void opponentLeft(String opponentName) throws RemoteException {
            leftNotificationReceived = true;
        }
    }
}
