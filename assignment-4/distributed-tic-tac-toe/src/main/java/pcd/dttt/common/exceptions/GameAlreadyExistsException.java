package pcd.dttt.common.exceptions;

import java.io.Serial;

// Raised when a room name is already in use.
public class GameAlreadyExistsException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    // Builds the exception with a readable error message.
    public GameAlreadyExistsException(String message) {
        super(message);
    }
}
