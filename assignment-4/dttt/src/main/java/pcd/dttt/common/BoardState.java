package pcd.dttt.common;

import java.io.Serializable;
import java.util.Arrays;

/**
 * An immutable value object representing a snapshot of the Tic-Tac-Toe game board and metadata.
 * Sent across the network from server to client to update players on the game state.
 *
 * <p>Implements {@link Serializable} to allow network transmission via RMI.</p>
 */
public final class BoardState implements Serializable {
    private static final long serialVersionUID = 1L;

    /** The 3x3 board representation containing characters ' ', 'X', or 'O'. */
    private final char[][] grid;

    /** The nickname of Player X (the creator). */
    private final String playerX;

    /** The nickname of Player O (the opponent). Null if waiting for an opponent. */
    private final String playerO;

    /** The nickname of the player whose turn it is. Null if the game is waiting or terminated. */
    private final String turnOf;

    /** The current status of the game match. */
    private final GameStatus status;

    /**
     * Creates a new BoardState snapshot.
     * Performs a deep copy of the grid array to maintain absolute immutability of this object.
     *
     * @param grid the current 3x3 board state
     * @param playerX the nickname of Player X
     * @param playerO the nickname of Player O
     * @param turnOf the nickname of the player whose turn it is
     * @param status the current game status
     */
    public BoardState(char[][] grid, String playerX, String playerO, String turnOf, GameStatus status) {
        // Deep copy the grid to ensure immutability
        this.grid = new char[3][3];
        for (int i = 0; i < 3; i++) {
            this.grid[i] = Arrays.copyOf(grid[i], 3);
        }
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
    public char[][] getGrid() {
        char[][] copy = new char[3][3];
        for (int i = 0; i < 3; i++) {
            copy[i] = Arrays.copyOf(this.grid[i], 3);
        }
        return copy;
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
        if (row < 0 || row >= 3 || col < 0 || col >= 3) {
            throw new IllegalArgumentException("Grid indices must be between 0 and 2");
        }
        return grid[row][col];
    }

    /**
     * Gets the nickname of Player X.
     *
     * @return the name of Player X
     */
    public String getPlayerX() {
        return playerX;
    }

    /**
     * Gets the nickname of Player O.
     *
     * @return the name of Player O, or null if waiting for player
     */
    public String getPlayerO() {
        return playerO;
    }

    /**
     * Gets the name of the player whose turn it is.
     *
     * @return the name of the active player, or null if game is not active
     */
    public String getTurnOf() {
        return turnOf;
    }

    /**
     * Gets the current status of the game match.
     *
     * @return the game status
     */
    public GameStatus getStatus() {
        return status;
    }

    /**
     * Renders a human-readable text representation of the Tic-Tac-Toe board and metadata.
     * Excellent for CLI display.
     *
     * @return a multi-line string representing the board state
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Status: ").append(status).append("\n");
        sb.append("Player X: ").append(playerX != null ? playerX : "<waiting>").append("\n");
        sb.append("Player O: ").append(playerO != null ? playerO : "<waiting>").append("\n");
        if (status == GameStatus.ACTIVE) {
            sb.append("Turn: ").append(turnOf).append("\n");
        }
        sb.append("Grid:\n");
        for (int r = 0; r < 3; r++) {
            sb.append(" ").append(grid[r][0]).append(" | ").append(grid[r][1]).append(" | ").append(grid[r][2]).append(" \n");
            if (r < 2) {
                sb.append("---+---+---\n");
            }
        }
        return sb.toString();
    }
}
