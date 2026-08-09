package pcd.poool.model.physics.common;

/** Strategy interface for advancing a mutable board state. */
public interface PhysicsStepper {

    /** Advances the board by the given elapsed time. */
    void step(Board board, long elapsedMillis);
}
