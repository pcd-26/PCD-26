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

// Server-side authority for one distributed Tic-Tac-Toe match.
public class GameImpl extends UnicastRemoteObject implements Game {
    private static final long serialVersionUID = 1L;
    private static final int BOARD_SIZE = BoardState.BOARD_SIZE;
    private static final char EMPTY_CELL = ' ';

    private final String gameName;
    private final char[][] boardGrid;
    private final String playerXName;
    private final PlayerClient playerXClient;
    private String playerOName;
    private PlayerClient playerOClient;
    private String currentTurnPlayerName;
    private GameStatus gameStatus;
    private final transient ExecutorService callbackExecutorService = Executors.newVirtualThreadPerTaskExecutor();
    private boolean isClosed;

    // Creates a waiting room with player X already registered.
    public GameImpl(String gameName, String playerXName, PlayerClient playerXClient) throws RemoteException {
        super(0);
        this.gameName = gameName;
        this.playerXName = playerXName;
        this.playerXClient = playerXClient;
        this.gameStatus = GameStatus.WAITING;
        this.boardGrid = new char[BOARD_SIZE][BOARD_SIZE];
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                boardGrid[r][c] = EMPTY_CELL;
            }
        }
    }

    // Returns the room name.
    @Override
    public String getName() throws RemoteException {
        return gameName;
    }

    // Registers player O and starts the match.
    public synchronized void joinSecondPlayer(String joinerName, PlayerClient joinerClient) throws GameFullException {
        ensureOpen();
        ensureWaiting();
        ensureDistinctPlayers(joinerName);

        this.playerOName = joinerName;
        this.playerOClient = joinerClient;
        this.gameStatus = GameStatus.ACTIVE;
        this.currentTurnPlayerName = playerXName;

        BoardState startingBoardState = snapshotState();
        notifyGameStarted(startingBoardState);
    }

    // Keeps compatibility with existing tests and callers.
    public void join(String joinerName, PlayerClient joinerClient) throws GameFullException {
        joinSecondPlayer(joinerName, joinerClient);
    }

    // Applies one validated move and broadcasts the new state.
    @Override
    public void makeMove(String playerName, int row, int col)
            throws RemoteException, NotYourTurnException, InvalidMoveException {
        BoardState updatedBoardState;
        synchronized (this) {
            // Validate that the request is legal for the current match state.
            ensureOpen();
            ensureActive();
            ensurePlayerTurn(playerName);
            ensureMoveInBounds(row, col);
            ensureCellEmpty(row, col);

            // Apply the move and advance the state machine.
            char playerMark = markFor(playerName);
            boardGrid[row][col] = playerMark;

            gameStatus = resolveStatusAfterMove(playerName, playerMark);
            currentTurnPlayerName = gameStatus.isActive() ? opponentNameOf(playerName) : null;

            // Capture one immutable snapshot for the callbacks.
            updatedBoardState = snapshotState();
        }

        notifyGameUpdated(updatedBoardState);
    }

    // Abandons the match and informs the remaining player.
    @Override
    public void leaveGame(String playerName) throws RemoteException {
        BoardState finalBoardState;
        PlayerClient opponentClient;
        PlayerClient leavingClient;

        synchronized (this) {
            ensureOpen();
            if (gameStatus.isTerminal()) {
                return;
            }

            // Move the match to a terminal state before releasing the lock.
            gameStatus = GameStatus.ABANDONED;
            currentTurnPlayerName = null;
            finalBoardState = snapshotState();

            opponentClient = opponentClientOf(playerName);
            leavingClient = clientOf(playerName);
        }

        // Send the final snapshot to the leaving player and notify the opponent.
        notifyStateToClient(leavingClient, finalBoardState);
        notifyOpponentLeftNow(opponentClient, playerName, finalBoardState);

        shutdownCallbackExecutor();
    }

    // Returns the latest immutable state snapshot.
    @Override
    public synchronized BoardState getBoardState() throws RemoteException {
        ensureOpen();
        return snapshotState();
    }

    // Copies the current match state into an immutable value object.
    private BoardState snapshotState() {
        return new BoardState(boardGrid, playerXName, playerOName, currentTurnPlayerName, gameStatus);
    }

    // Checks whether one mark completed a winning line.
    private boolean hasWinningLine(char mark) {
        for (int row = 0; row < BOARD_SIZE; row++) {
            if (lineMatches(boardGrid[row][0], boardGrid[row][1], boardGrid[row][2], mark)) {
                return true;
            }
        }
        for (int column = 0; column < BOARD_SIZE; column++) {
            if (lineMatches(boardGrid[0][column], boardGrid[1][column], boardGrid[2][column], mark)) {
                return true;
            }
        }
        return lineMatches(boardGrid[0][0], boardGrid[1][1], boardGrid[2][2], mark)
            || lineMatches(boardGrid[0][BOARD_SIZE - 1], boardGrid[1][1], boardGrid[2][0], mark);
    }

    // Checks whether no empty cell is left.
    private boolean isBoardFull() {
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int column = 0; column < BOARD_SIZE; column++) {
                if (boardGrid[row][column] == EMPTY_CELL) {
                    return false;
                }
            }
        }
        return true;
    }

    // Broadcasts the start event outside the game lock.
    private void notifyGameStarted(BoardState startingBoardState) {
        callbackExecutorService.submit(() -> {
            if (!sendGameStartedCallback(playerXClient, playerXName, startingBoardState)) {
                return;
            }
            sendGameStartedCallback(playerOClient, playerOName, startingBoardState);
        });
    }

    // Broadcasts a board update outside the game lock.
    private void notifyGameUpdated(BoardState updatedBoardState) {
        callbackExecutorService.submit(() -> {
            sendGameUpdatedCallback(playerXClient, playerXName, updatedBoardState);
            sendGameUpdatedCallback(playerOClient, playerOName, updatedBoardState);
        });

        if (updatedBoardState.status().isTerminal()) {
            shutdownCallbackExecutor();
        }
    }

    // Converts a callback failure into a terminal abandoned game.
    private synchronized void handleClientDisconnect(String disconnectedPlayer) {
        if (gameStatus.isTerminal()) {
            return;
        }
        gameStatus = GameStatus.ABANDONED;
        currentTurnPlayerName = null;
        BoardState abandonedBoardState = snapshotState();

        PlayerClient opponentClient = opponentClientOf(disconnectedPlayer);

        if (opponentClient != null) {
            callbackExecutorService.submit(() -> {
                try {
                    opponentClient.opponentLeft(disconnectedPlayer);
                    opponentClient.gameUpdated(abandonedBoardState);
                } catch (RemoteException exception) {
                    // Both players disconnected, ignore.
                }
            });
        }
        shutdownCallbackExecutor();
    }

    // Stops the callback executor after the match ends.
    private void shutdownCallbackExecutor() {
        callbackExecutorService.shutdown();
    }

    // Ensures that player O can still join.
    private void ensureWaiting() throws GameFullException {
        if (!gameStatus.isWaiting()) {
            throw new GameFullException("Game is not in WAITING state.");
        }
    }

    // Rejects equal names for the two players.
    private void ensureDistinctPlayers(String joinerName) {
        if (joinerName.equals(playerXName)) {
            throw new IllegalArgumentException("Opponent name cannot be identical to the creator's name.");
        }
    }

    // Ensures that moves are still allowed.
    private void ensureActive() throws InvalidMoveException {
        if (!gameStatus.isActive()) {
            throw new InvalidMoveException("Game is not active (current status: " + gameStatus + ").");
        }
    }

    // Ensures that the caller owns the current turn.
    private void ensurePlayerTurn(String playerName) throws NotYourTurnException {
        if (currentTurnPlayerName == null || !currentTurnPlayerName.equals(playerName)) {
            throw new NotYourTurnException("It is not your turn.");
        }
    }

    // Ensures that the requested cell exists.
    private void ensureMoveInBounds(int row, int col) throws InvalidMoveException {
        if (row < 0 || row >= BOARD_SIZE || col < 0 || col >= BOARD_SIZE) {
            throw new InvalidMoveException("Invalid coordinates: (" + row + ", " + col + ")");
        }
    }

    // Ensures that the selected cell is still free.
    private void ensureCellEmpty(int row, int col) throws InvalidMoveException {
        if (boardGrid[row][col] != EMPTY_CELL) {
            throw new InvalidMoveException("Cell (" + row + ", " + col + ") is already occupied.");
        }
    }

    // Maps a player name to its board mark.
    private char markFor(String playerName) {
        return playerName.equals(playerXName) ? 'X' : 'O';
    }

    // Resolves whether the move won, drew, or keeps the game active.
    private GameStatus resolveStatusAfterMove(String playerName, char mark) {
        if (hasWinningLine(mark)) {
            return playerName.equals(playerXName) ? GameStatus.WON_X : GameStatus.WON_O;
        }
        if (isBoardFull()) {
            return GameStatus.DRAW;
        }
        return GameStatus.ACTIVE;
    }

    // Returns the name of the other player.
    private String opponentNameOf(String playerName) {
        return playerName.equals(playerXName) ? playerOName : playerXName;
    }

    // Returns the callback client of the given player.
    private PlayerClient clientOf(String playerName) {
        return playerName.equals(playerXName) ? playerXClient : playerOClient;
    }

    // Returns the callback client of the other player.
    private PlayerClient opponentClientOf(String playerName) {
        return playerName.equals(playerXName) ? playerOClient : playerXClient;
    }

    // Returns true when three cells contain the same mark.
    private boolean lineMatches(char first, char second, char third, char mark) {
        return first == mark && second == mark && third == mark;
    }

    // Sends one game-start callback and handles disconnects.
    private boolean sendGameStartedCallback(PlayerClient playerClient, String playerName, BoardState boardState) {
        if (playerClient == null) {
            return true;
        }
        try {
            playerClient.gameStarted(boardState);
            return true;
        } catch (RemoteException exception) {
            handleClientDisconnect(playerName);
            return false;
        }
    }

    // Sends one board-update callback and handles disconnects.
    private void sendGameUpdatedCallback(PlayerClient playerClient, String playerName, BoardState boardState) {
        if (playerClient == null) {
            return;
        }
        try {
            playerClient.gameUpdated(boardState);
        } catch (RemoteException exception) {
            handleClientDisconnect(playerName);
        }
    }

    // Notifies the opponent synchronously during an explicit leave.
    private void notifyOpponentLeftNow(PlayerClient playerClient, String playerName, BoardState boardState) {
        if (playerClient == null) {
            return;
        }
        try {
            playerClient.opponentLeft(playerName);
            playerClient.gameUpdated(boardState);
        } catch (RemoteException exception) {
            // Ignore disconnects during shutdown.
        }
    }

    // Sends a final board snapshot to one client.
    private void notifyStateToClient(PlayerClient playerClient, BoardState boardState) {
        if (playerClient == null) {
            return;
        }
        callbackExecutorService.submit(() -> {
            try {
                playerClient.gameUpdated(boardState);
            } catch (RemoteException exception) {
                // Ignore client disconnection on exit.
            }
        });
    }

    // Closes the room and unexports its remote object.
    public synchronized void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        shutdownCallbackExecutor();
        try {
            UnicastRemoteObject.unexportObject(this, true);
        } catch (NoSuchObjectException exception) {
            // Already unexported; ignore.
        }
    }

    // Exposes executor shutdown state for tests.
    boolean isCallbackExecutorShutdown() {
        return callbackExecutorService.isShutdown();
    }

    // Fails fast if the room has already been closed.
    private void ensureOpen() {
        if (isClosed) {
            throw new IllegalStateException("Game has been closed.");
        }
    }
}
