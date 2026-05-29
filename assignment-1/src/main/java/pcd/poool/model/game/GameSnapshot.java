package pcd.poool.model.game;

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

    public boolean isFinished() {
        return status == GameStatus.FINISHED;
    }
}
