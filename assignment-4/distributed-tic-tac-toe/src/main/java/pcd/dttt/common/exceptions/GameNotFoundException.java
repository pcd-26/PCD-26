package pcd.dttt.common.exceptions;

import java.io.Serial;

// Raised when a requested room does not exist.
public class GameNotFoundException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    // Builds the exception with a readable error message.
    public GameNotFoundException(String message) {
        super(message);
    }
}
