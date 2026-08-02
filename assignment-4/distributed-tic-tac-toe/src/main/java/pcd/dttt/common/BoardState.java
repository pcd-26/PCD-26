package pcd.dttt.common;

import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * An immutable value object representing a snapshot of the Tic-Tac-Toe game board and metadata.
 * Sent across the network from server to client to update players on the game state.
 *
 * <p>Implements {@link Serializable} to allow network transmission via RMI.</p>
 *
 * @param grid    The 3x3 board representation containing characters ' ', 'X', or 'O'.
 * @param playerX The nickname of Player X (the creator).
 * @param playerO The nickname of Player O (the opponent). Null if waiting for an opponent.
 * @param turnOf  The nickname of the player whose turn it is. Null if the game is waiting or terminated.
 * @param status  The current status of the game match.
 */
public record BoardState(char[][] grid, String playerX, String playerO, String turnOf,
                         GameStatus status) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** The board is always a 3x3 grid. */
    public static final int BOARD_SIZE = 3;

    /**
     * Creates a new BoardState snapshot.
     * Performs a deep copy of the grid array to maintain absolute immutability of this object.
     *
     * @param grid    the current 3x3 board state
     * @param playerX the nickname of Player X
     * @param playerO the nickname of Player O
     * @param turnOf  the nickname of the player whose turn it is
     * @param status  the current game status
     */
    public BoardState(char[][] grid, String playerX, String playerO, String turnOf, GameStatus status) {
        this.grid = copyGrid(grid);
        this.playerX = playerX;
        this.playerO = playerO;
        this.turnOf = turnOf;
        this.status = status;
    }

    /**
     * Returns a copy of the 3x3 board grid.
     *
     * @return a deep copy of the grid array
     */
    @Override
    public char[][] grid() {
        return copyGrid(this.grid);
    }

    /**
     * Gets the mark at the specified position.
     *
     * @param row zero-indexed row index (0, 1, or 2)
     * @param col zero-indexed column index (0, 1, or 2)
     * @return the character mark at that coordinate (' ', 'X', or 'O')
     * @throws IllegalArgumentException if the coordinates are out of bounds
     */
    public char getMark(int row, int col) {
        if (row < 0 || row >= BOARD_SIZE || col < 0 || col >= BOARD_SIZE) {
            throw new IllegalArgumentException("Grid indices must be between 0 and 2");
        }
        return grid[row][col];
    }

    /**
     * Gets the nickname of Player X.
     *
     * @return the name of Player X
     */
    @NotNull
    @Override
    public String playerX() {
        return playerX;
    }

    /**
     * Gets the nickname of Player O.
     *
     * @return the name of Player O, or null if waiting for player
     */
    @Override
    public String playerO() {
        return playerO;
    }

    /**
     * Gets the name of the player whose turn it is.
     *
     * @return the name of the active player, or null if game is not active
     */
    @Override
    public String turnOf() {
        return turnOf;
    }

    /**
     * Gets the current status of the game match.
     *
     * @return the game status
     */
    @NotNull
    @Override
    public GameStatus status() {
        return status;
    }

    /**
     * Renders a human-readable text representation of the Tic-Tac-Toe board and metadata.
     * Excellent for CLI display.
     *
     * @return a multi-line string representing the board state
     */
    @NotNull
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Status: ").append(status).append("\n");
        sb.append("Player X: ").append(playerX != null ? playerX : "<waiting>").append("\n");
        sb.append("Player O: ").append(playerO != null ? playerO : "<waiting>").append("\n");
        if (status.isActive()) {
            sb.append("Turn: ").append(turnOf).append("\n");
        }
        sb.append("Grid:\n");
        for (int r = 0; r < BOARD_SIZE; r++) {
            sb.append(" ").append(grid[r][0]).append(" | ").append(grid[r][1]).append(" | ").append(grid[r][2]).append(" \n");
            if (r < BOARD_SIZE - 1) {
                sb.append("---+---+---\n");
            }
        }
        return sb.toString();
    }

    /** Makes a deep copy of the 3x3 grid. */
    private static char[][] copyGrid(char[][] source) {
        char[][] copy = new char[BOARD_SIZE][BOARD_SIZE];
        for (int row = 0; row < BOARD_SIZE; row++) {
            copy[row] = Arrays.copyOf(source[row], BOARD_SIZE);
        }
        return copy;
    }
}
