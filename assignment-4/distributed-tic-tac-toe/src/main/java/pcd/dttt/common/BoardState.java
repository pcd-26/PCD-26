package pcd.dttt.common;

import org.jetbrains.annotations.NotNull;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

// Immutable snapshot sent from server to clients.
public record BoardState(char[][] grid, String playerX, String playerO, String turnOf,
                         GameStatus status) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final int BOARD_SIZE = 3;

    // Copies the board so the snapshot stays immutable.
    public BoardState(char[][] grid, String playerX, String playerO, String turnOf, GameStatus status) {
        this.grid = copyGrid(grid);
        this.playerX = playerX;
        this.playerO = playerO;
        this.turnOf = turnOf;
        this.status = status;
    }

    // Returns a defensive copy of the board.
    @Override
    public char[][] grid() {
        return copyGrid(this.grid);
    }

    // Reads one board cell with bounds checking.
    public char getMark(int row, int col) {
        if (row < 0 || row >= BOARD_SIZE || col < 0 || col >= BOARD_SIZE) {
            throw new IllegalArgumentException("Grid indices must be between 0 and 2");
        }
        return grid[row][col];
    }

    // Exposes player X name.
    @NotNull
    @Override
    public String playerX() {
        return playerX;
    }

    // Exposes player O name when present.
    @Override
    public String playerO() {
        return playerO;
    }

    // Exposes whose turn it is while the game is active.
    @Override
    public String turnOf() {
        return turnOf;
    }

    // Exposes the current game status.
    @NotNull
    @Override
    public GameStatus status() {
        return status;
    }

    // Renders a CLI-friendly board view.
    @NotNull
    @Override
    public String toString() {
        StringBuilder renderedBoard = new StringBuilder();
        renderedBoard.append("Status: ").append(status).append("\n");
        renderedBoard.append("Player X: ").append(playerX != null ? playerX : "<waiting>").append("\n");
        renderedBoard.append("Player O: ").append(playerO != null ? playerO : "<waiting>").append("\n");
        if (status.isActive()) {
            renderedBoard.append("Turn: ").append(turnOf).append("\n");
        }
        renderedBoard.append("Grid:\n");
        for (int row = 0; row < BOARD_SIZE; row++) {
            renderedBoard.append(" ").append(grid[row][0]).append(" | ").append(grid[row][1]).append(" | ").append(grid[row][2]).append(" \n");
            if (row < BOARD_SIZE - 1) {
                renderedBoard.append("---+---+---\n");
            }
        }
        return renderedBoard.toString();
    }

    // Deep-copies the fixed 3x3 board.
    private static char[][] copyGrid(char[][] sourceGrid) {
        char[][] copiedGrid = new char[BOARD_SIZE][BOARD_SIZE];
        for (int row = 0; row < BOARD_SIZE; row++) {
            copiedGrid[row] = Arrays.copyOf(sourceGrid[row], BOARD_SIZE);
        }
        return copiedGrid;
    }
}
