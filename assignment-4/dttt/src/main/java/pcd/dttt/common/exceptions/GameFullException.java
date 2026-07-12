package pcd.dttt.common.exceptions;

/**
 * Thrown when trying to join a game that already has two players.
 */
public class GameFullException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new GameFullException with the specified detail message.
     *
     * @param message the detail message
     */
    public GameFullException(String message) {
        super(message);
    }
}
