package pcd.poool.model.physics.common;

/**
 * Strategy interface for advancing a mutable board state.
 *
 * <p>The sequential and platform-threaded implementations share the same
 * board and game-rule model, but can use different stepping strategies. This
 * interface keeps the domain model reusable while allowing the threaded runner
 * to inject a worker-based physics implementation.
 */
public interface PhysicsStepper {

    /**
     * Advances the board by the given elapsed time.
     *
     * @param board board to mutate
     * @param elapsedMillis elapsed time in milliseconds
     */
    void step(Board board, long elapsedMillis);
}
