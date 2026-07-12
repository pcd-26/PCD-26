package pcd.dttt.common.exceptions;

/**
 * Thrown when trying to join or access a game that does not exist.
 */
public class GameNotFoundException extends Exception {
    private static final long serialVersionUID = 1L;

    public GameNotFoundException(String message) {
        super(message);
    }
}
