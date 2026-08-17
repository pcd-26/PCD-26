package pcd.dttt.common.exceptions;

import java.io.Serial;

// Raised when a player moves out of turn.
public class NotYourTurnException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    // Builds the exception with a readable error message.
    public NotYourTurnException(String message) {
        super(message);
    }
}
