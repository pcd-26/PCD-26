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
    ABANDONED
}
