package pcd.dttt.common.exceptions;

/**
 * Thrown when a player attempts to make a move but it is the opponent's turn.
 */
public class NotYourTurnException extends Exception {
    private static final long serialVersionUID = 1L;

    public NotYourTurnException(String message) {
        super(message);
    }
}
