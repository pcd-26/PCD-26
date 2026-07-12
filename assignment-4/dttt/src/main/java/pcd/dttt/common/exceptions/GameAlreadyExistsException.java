package pcd.dttt.common.exceptions;

/**
 * Thrown when trying to create a game with a name that is already taken.
 */
public class GameAlreadyExistsException extends Exception {
    private static final long serialVersionUID = 1L;

    public GameAlreadyExistsException(String message) {
        super(message);
    }
}
