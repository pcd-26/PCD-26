package pcd.poool.model.game;

/**
 * Coarse lifecycle state of a sequential Poool game.
 *
 * <p>This is intentionally not a turn marker. Player readiness is represented
 * separately in {@link GameSnapshot#humanCanShoot()} and
 * {@link GameSnapshot#botCanShoot()}.
 */
public enum GameStatus {
    /** The game is active and no ball is currently moving. */
    RUNNING_STILL,
    /** At least one ball is moving after a shot or collision. */
    BALLS_MOVING,
    /** A terminal condition has been reached. */
    FINISHED
}
