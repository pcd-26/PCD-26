package pcd.dttt.common.exceptions;

/**
 * Thrown when trying to join or access a game that does not exist.
 */
public class GameNotFoundException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new GameNotFoundException with the specified detail message.
     *
     * @param message the detail message
     */
    public GameNotFoundException(String message) {
        super(message);
    }
}
