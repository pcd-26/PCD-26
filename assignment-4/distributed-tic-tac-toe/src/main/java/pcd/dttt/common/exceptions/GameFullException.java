package pcd.dttt.common.exceptions;

import java.io.Serial;

// Raised when a room already has two players.
public class GameFullException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    // Builds the exception with a readable error message.
    public GameFullException(String message) {
        super(message);
    }
}
