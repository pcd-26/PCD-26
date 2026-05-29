package pcd.poool.model.game;

/**
 * Immutable view of the current game state.
 *
 * <p>The Swing view and benchmarks read this record instead of inspecting
 * mutable gameplay fields directly. {@code gameOverReason} is {@code null}
 * while the game is still running.
 */
public record GameSnapshot(
        int humanScore,
        int botScore,
        GameStatus status,
        Player winner,
        GameOverReason gameOverReason,
        boolean humanCanShoot,
        boolean botCanShoot,
        long elapsedMillis,
        long simulatedSteps,
        double averageStepMillis) {

    /**
     * @return whether the game lifecycle has reached a terminal state
     */
    public boolean isFinished() {
        return status == GameStatus.FINISHED;
    }
}
