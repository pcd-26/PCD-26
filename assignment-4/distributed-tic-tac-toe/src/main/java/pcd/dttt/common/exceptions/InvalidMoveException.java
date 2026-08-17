package pcd.dttt.common.exceptions;

import java.io.Serial;

// Raised when a move is outside the game rules.
public class InvalidMoveException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    // Builds the exception with a readable error message.
    public InvalidMoveException(String message) {
        super(message);
    }
}
