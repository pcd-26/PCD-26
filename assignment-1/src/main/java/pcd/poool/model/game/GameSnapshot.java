package pcd.poool.model.game;

public record GameSnapshot(
        int humanScore,
        int botScore,
        Player currentPlayer,
        GameStatus status,
        Player winner,
        long elapsedMillis,
        long simulatedSteps,
        double averageStepMillis) {

    public boolean isFinished() {
        return status == GameStatus.FINISHED;
    }
}
