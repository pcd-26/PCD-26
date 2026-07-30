package pcd.dttt.common.exceptions;

import java.io.Serial;

/**
 * Thrown when a player attempts an invalid move (e.g., out of bounds, cell already occupied, game not active).
 */
public class InvalidMoveException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new InvalidMoveException with the specified detail message.
     *
     * @param message the detail message
     */
    public InvalidMoveException(String message) {
        super(message);
    }
}
