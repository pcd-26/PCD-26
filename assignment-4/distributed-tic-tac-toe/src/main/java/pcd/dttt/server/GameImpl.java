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
    private static final int BOARD_SIZE = BoardState.BOARD_SIZE;
    private static final char EMPTY_CELL = ' ';

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
        this.grid = new char[BOARD_SIZE][BOARD_SIZE];
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                grid[r][c] = EMPTY_CELL;
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
        ensureWaiting();
        ensureDistinctPlayers(joinerName);

        this.playerOName = joinerName;
        this.playerOClient = joinerClient;
        this.status = GameStatus.ACTIVE;
        this.turnOf = playerXName;

        BoardState state = snapshotState();
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
            ensureActive();
            ensurePlayerTurn(playerName);
            ensureMoveInBounds(row, col);
            ensureCellEmpty(row, col);

            char mark = markFor(playerName);
            grid[row][col] = mark;

            status = resolveStatusAfterMove(playerName, mark);
            turnOf = status.isActive() ? opponentNameOf(playerName) : null;

            state = snapshotState();
        }

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
        PlayerClient leavingClient;

        synchronized (this) {
            ensureOpen();
            if (status.isTerminal()) {
                return;
            }
            status = GameStatus.ABANDONED;
            turnOf = null;
            state = snapshotState();

            opponentClient = opponentClientOf(playerName);
            leavingClient = clientOf(playerName);
        }

        notifyStateToClient(leavingClient, state);
        notifyOpponentLeft(opponentClient, playerName, state);
        
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
        return snapshotState();
    }

    /**
     * Creates a {@link BoardState} snapshot of the current state.
     * Assumes monitor lock on {@code this} is held by caller.
     *
     * @return the BoardState snapshot
     */
    private BoardState snapshotState() {
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
        for (int r = 0; r < BOARD_SIZE; r++) {
            if (lineMatches(grid[r][0], grid[r][1], grid[r][2], mark)) return true;
        }
        // Columns
        for (int c = 0; c < BOARD_SIZE; c++) {
            if (lineMatches(grid[0][c], grid[1][c], grid[2][c], mark)) return true;
        }
        // Diagonals
        if (lineMatches(grid[0][0], grid[1][1], grid[2][2], mark)) return true;
        if (lineMatches(grid[0][BOARD_SIZE - 1], grid[1][1], grid[2][0], mark)) return true;
        return false;
    }

    /**
     * Checks if there are no empty cells left on the board.
     * Assumes monitor lock on {@code this} is held.
     *
     * @return true if board is full, false otherwise
     */
    private boolean isBoardFull() {
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                if (grid[r][c] == EMPTY_CELL) return false;
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
            if (!notifyStarted(playerXClient, playerXName, state)) {
                return;
            }
            notifyStarted(playerOClient, playerOName, state);
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
            notifyUpdated(playerXClient, playerXName, state);
            notifyUpdated(playerOClient, playerOName, state);
        });

        if (state.status().isTerminal()) {
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
        if (status.isTerminal()) {
            return;
        }
        status = GameStatus.ABANDONED;
        turnOf = null;
        BoardState state = snapshotState();

        PlayerClient opponentClient = opponentClientOf(disconnectedPlayer);

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

    /** Ensures the game is still waiting for an opponent. */
    private void ensureWaiting() throws GameFullException {
        if (!status.isWaiting()) {
            throw new GameFullException("Game is not in WAITING state.");
        }
    }

    /** Ensures the joiner is not reusing the creator's nickname. */
    private void ensureDistinctPlayers(String joinerName) {
        if (joinerName.equals(playerXName)) {
            throw new IllegalArgumentException("Opponent name cannot be identical to the creator's name.");
        }
    }

    /** Ensures the game is currently active. */
    private void ensureActive() throws InvalidMoveException {
        if (!status.isActive()) {
            throw new InvalidMoveException("Game is not active (current status: " + status + ").");
        }
    }

    /** Ensures the requested player is the one whose turn it is. */
    private void ensurePlayerTurn(String playerName) throws NotYourTurnException {
        if (turnOf == null || !turnOf.equals(playerName)) {
            throw new NotYourTurnException("It is not your turn.");
        }
    }

    /** Ensures the coordinates are inside the board. */
    private void ensureMoveInBounds(int row, int col) throws InvalidMoveException {
        if (row < 0 || row >= BOARD_SIZE || col < 0 || col >= BOARD_SIZE) {
            throw new InvalidMoveException("Invalid coordinates: (" + row + ", " + col + ")");
        }
    }

    /** Ensures the chosen cell is still empty. */
    private void ensureCellEmpty(int row, int col) throws InvalidMoveException {
        if (grid[row][col] != EMPTY_CELL) {
            throw new InvalidMoveException("Cell (" + row + ", " + col + ") is already occupied.");
        }
    }

    /** Returns the board mark assigned to the given player. */
    private char markFor(String playerName) {
        return playerName.equals(playerXName) ? 'X' : 'O';
    }

    /** Resolves the next game status after a move. */
    private GameStatus resolveStatusAfterMove(String playerName, char mark) {
        if (checkWin(mark)) {
            return playerName.equals(playerXName) ? GameStatus.WON_X : GameStatus.WON_O;
        }
        if (isBoardFull()) {
            return GameStatus.DRAW;
        }
        return GameStatus.ACTIVE;
    }

    /** Returns the other player's name. */
    private String opponentNameOf(String playerName) {
        return playerName.equals(playerXName) ? playerOName : playerXName;
    }

    /** Returns the client associated with the given player. */
    private PlayerClient clientOf(String playerName) {
        return playerName.equals(playerXName) ? playerXClient : playerOClient;
    }

    /** Returns the opponent client for the given player. */
    private PlayerClient opponentClientOf(String playerName) {
        return playerName.equals(playerXName) ? playerOClient : playerXClient;
    }

    /** Returns true when three cells contain the same mark. */
    private boolean lineMatches(char first, char second, char third, char mark) {
        return first == mark && second == mark && third == mark;
    }

    /** Notifies a client that the game has started, ignoring disconnections. */
    private boolean notifyStarted(PlayerClient client, String playerName, BoardState state) {
        if (client == null) {
            return true;
        }
        try {
            client.gameStarted(state);
            return true;
        } catch (RemoteException e) {
            handleClientDisconnect(playerName);
            return false;
        }
    }

    /** Notifies a client that the board changed, ignoring disconnections. */
    private void notifyUpdated(PlayerClient client, String playerName, BoardState state) {
        if (client == null) {
            return;
        }
        try {
            client.gameUpdated(state);
        } catch (RemoteException e) {
            handleClientDisconnect(playerName);
        }
    }

    /** Notifies the opponent that a player left, if the opponent is still connected. */
    private void notifyOpponentLeft(PlayerClient client, String playerName, BoardState state) {
        if (client == null) {
            return;
        }
        callbackExecutor.submit(() -> {
            try {
                client.opponentLeft(playerName);
                client.gameUpdated(state);
            } catch (RemoteException e) {
                // Ignore disconnects during shutdown.
            }
        });
    }

    /** Sends the final board state to a client, if present. */
    private void notifyStateToClient(PlayerClient client, BoardState state) {
        if (client == null) {
            return;
        }
        callbackExecutor.submit(() -> {
            try {
                client.gameUpdated(state);
            } catch (RemoteException e) {
                // Ignore client disconnection on exit.
            }
        });
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
