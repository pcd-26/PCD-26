package pcd.dttt.server;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.NoSuchObjectException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.Game;
import pcd.dttt.common.GameStatus;
import pcd.dttt.common.PlayerClient;
import pcd.dttt.common.exceptions.InvalidMoveException;
import pcd.dttt.common.exceptions.NotYourTurnException;
import pcd.dttt.common.exceptions.GameFullException;

/**
 * Implementation of the {@link Game} RMI remote interface.
 * Controls the state machine, turns, win conditions, and client callbacks for a single Tic-Tac-Toe match.
 * 
 * <p><strong>Concurrency Strategy:</strong></p>
 * <ul>
 *   <li><b>State Ownership:</b> All mutable game variables (the board grid, active turn, status, and client references)
 *       are owned by the {@code GameImpl} instance.</li>
 *   <li><b>Monitor Lock:</b> All actions mutating or inspecting the game state are synchronized on {@code this}
 *       (using {@code synchronized} methods or blocks) to enforce thread-safety across multiple RMI thread pool calls.</li>
 *   <li><b>Open Call Pattern:</b> To prevent deadlocks and ensure high responsiveness, RMI callbacks to client objects
 *       are dispatched <i>outside</i> of the synchronized blocks. This guarantees that if a remote client hangs or lags
 *       during a network callback, it will not block other players trying to call the server.</li>
 *   <li><b>Virtual Threads:</b> Asynchronous callbacks are submitted to an {@link ExecutorService} backed by Java 21
 *       Virtual Threads, allowing lightweight concurrent execution without consuming platform thread resources.</li>
 * </ul>
 */
public class GameImpl extends UnicastRemoteObject implements Game {
    private static final long serialVersionUID = 1L;

    /** The unique name identifying this game room. */
    private final String name;

    /** The 3x3 board grid, initialized with ' ' (empty space). */
    private final char[][] grid;
    
    /** The nickname of Player X (the game creator). */
    private final String playerXName;

    /** The remote RMI stub reference for Player X's callback client. */
    private final PlayerClient playerXClient;
    
    /** The nickname of Player O (the opponent). Null until joined. */
    private String playerOName;

    /** The remote RMI stub reference for Player O's callback client. Null until joined. */
    private PlayerClient playerOClient;
    
    /** The nickname of the player whose turn it is currently. Null if match has not started or ended. */
    private String turnOf;

    /** The current status of the game match. */
    private GameStatus status;

    /** Virtual thread executor service used to perform non-blocking RMI callbacks to the clients. */
    private final transient ExecutorService callbackExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** True once the game has been explicitly closed and unexported. */
    private boolean closed;

    /**
     * Constructs a new game room created by Player X.
     * Starts in {@link GameStatus#WAITING} state, waiting for Player O to join.
     *
     * @param name the unique name of the game room
     * @param creatorName the nickname of Player X
     * @param creatorClient the callback stub for Player X
     * @throws RemoteException if an RMI error occurs during export
     */
    public GameImpl(String name, String creatorName, PlayerClient creatorClient) throws RemoteException {
        super(0); // Export on anonymous port
        this.name = name;
        this.playerXName = creatorName;
        this.playerXClient = creatorClient;
        this.status = GameStatus.WAITING;
        this.grid = new char[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                grid[r][c] = ' ';
            }
        }
    }

    /**
     * Gets the name of the game room.
     *
     * @return the game room name
     * @throws RemoteException if an RMI error occurs
     */
    @Override
    public String getName() throws RemoteException {
        return name;
    }

    /**
     * Joins the game room as Player O (the opponent).
     * Automatically transitions the game to {@link GameStatus#ACTIVE}, starts the turn loop
     * with Player X, and triggers the `gameStarted` callback to both players.
     *
     * @param joinerName the nickname of Player O joining the game
     * @param joinerClient the callback stub for Player O
     * @throws GameFullException if the game is already in progress or has finished
     * @throws IllegalArgumentException if the joiner tries to use the same name as the creator
     */
    public synchronized void join(String joinerName, PlayerClient joinerClient) throws GameFullException {
        ensureOpen();
        if (status != GameStatus.WAITING) {
            throw new GameFullException("Game is not in WAITING state.");
        }
        if (joinerName.equals(playerXName)) {
            throw new IllegalArgumentException("Opponent name cannot be identical to the creator's name.");
        }
        this.playerOName = joinerName;
        this.playerOClient = joinerClient;
        this.status = GameStatus.ACTIVE;
        this.turnOf = playerXName; // X always starts

        BoardState state = getBoardStateSnapshot();
        notifyGameStarted(state);
    }

    /**
     * Attempts to place a mark on the board.
     * Validates coordinates, turn turns, and checks if this move results in a win or a draw.
     * Pushes state updates to both clients using asynchronous callbacks.
     *
     * @param playerName the name of the player making the move
     * @param row zero-indexed row (0, 1, or 2)
     * @param col zero-indexed column (0, 1, or 2)
     * @throws RemoteException if an RMI error occurs
     * @throws NotYourTurnException if the turn belongs to the opponent
     * @throws InvalidMoveException if the coordinates are out of bounds, cell is occupied, or game is not active
     */
    @Override
    public void makeMove(String playerName, int row, int col) 
            throws RemoteException, NotYourTurnException, InvalidMoveException {
        
        BoardState state;
        synchronized (this) {
            ensureOpen();
            if (status != GameStatus.ACTIVE) {
                throw new InvalidMoveException("Game is not active (current status: " + status + ").");
            }
            if (turnOf == null || !turnOf.equals(playerName)) {
                throw new NotYourTurnException("It is not your turn.");
            }
            if (row < 0 || row >= 3 || col < 0 || col >= 3) {
                throw new InvalidMoveException("Invalid coordinates: (" + row + ", " + col + ")");
            }
            if (grid[row][col] != ' ') {
                throw new InvalidMoveException("Cell (" + row + ", " + col + ") is already occupied.");
            }

            // Place mark
            char mark = playerName.equals(playerXName) ? 'X' : 'O';
            grid[row][col] = mark;

            // Evaluate board status
            if (checkWin(mark)) {
                status = playerName.equals(playerXName) ? GameStatus.WON_X : GameStatus.WON_O;
                turnOf = null;
            } else if (isBoardFull()) {
                status = GameStatus.DRAW;
                turnOf = null;
            } else {
                // Switch turn
                turnOf = playerName.equals(playerXName) ? playerOName : playerXName;
            }

            state = getBoardStateSnapshot();
        }

        // Notify clients outside of the synchronized lock (Open Call pattern)
        notifyGameUpdated(state);
    }

    /**
     * Explicitly leaves the game room.
     * Transitions the game status to {@link GameStatus#ABANDONED} and notifies the opponent.
     *
     * @param playerName the name of the player leaving
     * @throws RemoteException if an RMI error occurs
     */
    @Override
    public void leaveGame(String playerName) throws RemoteException {
        BoardState state;
        PlayerClient opponentClient;
        String opponentName;
        PlayerClient leavingClient;

        synchronized (this) {
            ensureOpen();
            if (status == GameStatus.WON_X || status == GameStatus.WON_O || 
                status == GameStatus.DRAW || status == GameStatus.ABANDONED) {
                return; // Already ended
            }
            status = GameStatus.ABANDONED;
            turnOf = null;
            state = getBoardStateSnapshot();

            if (playerName.equals(playerXName)) {
                opponentClient = playerOClient;
                opponentName = playerOName;
                leavingClient = playerXClient;
            } else {
                opponentClient = playerXClient;
                opponentName = playerXName;
                leavingClient = playerOClient;
            }
        }

        // Notify leaving client of the final abandoned state
        if (leavingClient != null) {
            callbackExecutor.submit(() -> {
                try {
                    leavingClient.gameUpdated(state);
                } catch (RemoteException e) {
                    // Ignore client disconnection on exit
                }
            });
        }

        // Notify opponent
        if (opponentClient != null) {
            callbackExecutor.submit(() -> {
                try {
                    opponentClient.opponentLeft(playerName);
                    opponentClient.gameUpdated(state);
                } catch (RemoteException e) {
                    // Ignore
                }
            });
        }
        
        shutdownExecutor();
    }

    /**
     * Retrieves the current board state snapshot.
     *
     * @return the current BoardState
     * @throws RemoteException if an RMI error occurs
     */
    @Override
    public synchronized BoardState getBoardState() throws RemoteException {
        ensureOpen();
        return getBoardStateSnapshot();
    }

    /**
     * Creates a {@link BoardState} snapshot of the current state.
     * Assumes monitor lock on {@code this} is held by caller.
     *
     * @return the BoardState snapshot
     */
    private BoardState getBoardStateSnapshot() {
        return new BoardState(grid, playerXName, playerOName, turnOf, status);
    }

    /**
     * Checks if the last move completed a line (row, column, or diagonal) of the specified mark.
     * Assumes monitor lock on {@code this} is held.
     *
     * @param mark the mark to check ('X' or 'O')
     * @return true if the mark won, false otherwise
     */
    private boolean checkWin(char mark) {
        // Rows
        for (int r = 0; r < 3; r++) {
            if (grid[r][0] == mark && grid[r][1] == mark && grid[r][2] == mark) return true;
        }
        // Columns
        for (int c = 0; c < 3; c++) {
            if (grid[0][c] == mark && grid[1][c] == mark && grid[2][c] == mark) return true;
        }
        // Diagonals
        if (grid[0][0] == mark && grid[1][1] == mark && grid[2][2] == mark) return true;
        if (grid[0][2] == mark && grid[1][1] == mark && grid[2][0] == mark) return true;
        return false;
    }

    /**
     * Checks if there are no empty cells left on the board.
     * Assumes monitor lock on {@code this} is held.
     *
     * @return true if board is full, false otherwise
     */
    private boolean isBoardFull() {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (grid[r][c] == ' ') return false;
            }
        }
        return true;
    }

    /**
     * Submits a virtual thread task to notify both players that the game has started.
     * Handles client disconnections dynamically.
     *
     * @param state the initial game board state
     */
    private void notifyGameStarted(BoardState state) {
        callbackExecutor.submit(() -> {
            try {
                playerXClient.gameStarted(state);
            } catch (RemoteException e) {
                handleClientDisconnect(playerXName);
                return;
            }

            try {
                playerOClient.gameStarted(state);
            } catch (RemoteException e) {
                handleClientDisconnect(playerOName);
            }
        });
    }

    /**
     * Submits a virtual thread task to notify players that the board state has updated.
     * If the state is terminal (victory, draw, or abandonment), shuts down the callback executor.
     *
     * @param state the updated board state
     */
    private void notifyGameUpdated(BoardState state) {
        callbackExecutor.submit(() -> {
            try {
                playerXClient.gameUpdated(state);
            } catch (RemoteException e) {
                handleClientDisconnect(playerXName);
            }

            if (playerOClient != null) {
                try {
                    playerOClient.gameUpdated(state);
                } catch (RemoteException e) {
                    handleClientDisconnect(playerOName);
                }
            }
        });

        if (state.status() != GameStatus.ACTIVE && state.status() != GameStatus.WAITING) {
            shutdownExecutor();
        }
    }

    /**
     * Handles the case where a player becomes unreachable (disconnects) during RMI callbacks.
     * Automatically transitions the game status to {@link GameStatus#ABANDONED} and alerts the remaining opponent.
     *
     * @param disconnectedPlayer the name of the player that is unreachable
     */
    private synchronized void handleClientDisconnect(String disconnectedPlayer) {
        if (status == GameStatus.WON_X || status == GameStatus.WON_O || 
            status == GameStatus.DRAW || status == GameStatus.ABANDONED) {
            return; // Game has already terminated
        }
        status = GameStatus.ABANDONED;
        turnOf = null;
        BoardState state = getBoardStateSnapshot();

        String opponentName = disconnectedPlayer.equals(playerXName) ? playerOName : playerXName;
        PlayerClient opponentClient = disconnectedPlayer.equals(playerXName) ? playerOClient : playerXClient;

        if (opponentClient != null) {
            callbackExecutor.submit(() -> {
                try {
                    opponentClient.opponentLeft(disconnectedPlayer);
                    opponentClient.gameUpdated(state);
                } catch (RemoteException e) {
                    // Both players disconnected, ignore
                }
            });
        }
        shutdownExecutor();
    }

    /**
     * Safely terminates the virtual thread callback executor.
     */
    private void shutdownExecutor() {
        callbackExecutor.shutdown();
    }

    /**
     * Closes the game, shuts down the callback executor, and unexports the remote object.
     * The method is idempotent so callers can use it during cleanup or pruning without
     * worrying about double close attempts.
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        shutdownExecutor();
        try {
            UnicastRemoteObject.unexportObject(this, true);
        } catch (NoSuchObjectException e) {
            // Already unexported; ignore.
        }
    }

    /**
     * Indicates whether the callback executor has been shut down.
     * Package-private to keep the production API minimal while allowing focused tests.
     */
    boolean isCallbackExecutorShutdown() {
        return callbackExecutor.isShutdown();
    }

    /**
     * Fails fast if the game has been closed and unexported.
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Game has been closed.");
        }
    }
}
