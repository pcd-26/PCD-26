package pcd.dttt.common.exceptions;

import java.io.Serial;

/**
 * Thrown when a player attempts to make a move, but it is the opponent's turn.
 */
public class NotYourTurnException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new NotYourTurnException with the specified detail message.
     *
     * @param message the detail message
     */
    public NotYourTurnException(String message) {
        super(message);
    }
}
