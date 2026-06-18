package pcd.poool.model.game;

/**
 * Immutable view of the current game state.
 *
 * <p>The Swing view and benchmarks read this record instead of inspecting
 * mutable gameplay fields directly. {@code gameOverReason} is {@code null}
 * while the game is still running.
 *
 * @param humanScore current human score
 * @param botScore current bot score
 * @param status current lifecycle state
 * @param winner winning player, or {@code null} for running games and draws
 * @param gameOverReason terminal reason, or {@code null} while running
 * @param humanCanShoot whether the human cue ball can currently be kicked
 * @param botCanShoot whether the bot cue ball can currently be kicked
 * @param elapsedMillis simulated game time
 * @param simulatedSteps number of completed simulation steps
 * @param averageStepMillis average physics step duration
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
     * Checks whether the game has reached a terminal state.
     *
     * @return whether the game lifecycle has reached a terminal state
     */
    public boolean isFinished() {
        return status == GameStatus.FINISHED;
    }
}
