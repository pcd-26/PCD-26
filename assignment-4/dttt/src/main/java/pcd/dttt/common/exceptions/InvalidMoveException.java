package pcd.dttt.common.exceptions;

/**
 * Thrown when a player attempts an invalid move (e.g., out of bounds, cell already occupied, game not active).
 */
public class InvalidMoveException extends Exception {
    private static final long serialVersionUID = 1L;

    public InvalidMoveException(String message) {
        super(message);
    }
}
