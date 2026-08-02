package pcd.dttt.common;

/**
 * Represents the current status of a Tic-Tac-Toe game.
 */
public enum GameStatus {
    /** The game is created and waiting for an opponent to join. */
    WAITING,
    
    /** The game is in progress. */
    ACTIVE,
    
    /** Player X has won the game. */
    WON_X,
    
    /** Player O has won the game. */
    WON_O,
    
    /** The game ended in a draw. */
    DRAW,
    
    /** A player left or disconnected before the game finished. */
    ABANDONED;

    /** Returns true when the game has not started yet. */
    public boolean isWaiting() {
        return this == WAITING;
    }

    /** Returns true when the game is still in progress. */
    public boolean isActive() {
        return this == ACTIVE;
    }

    /** Returns true when the match has ended. */
    public boolean isTerminal() {
        return switch (this) {
            case WON_X, WON_O, DRAW, ABANDONED -> true;
            default -> false;
        };
    }
}
