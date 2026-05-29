package pcd.poool.model.game;

import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.Board;
import pcd.poool.model.physics.BoardConf;

public class SequentialGame {

    private static final double MIN_SHOT_SPEED = 0.05;
    private static final double BOT_SHOT_SPEED = 1.2;
    private static final V2d FALLBACK_BOT_SHOT = new V2d(0.35, -1.0).getNormalized().mul(BOT_SHOT_SPEED);

    private final Board board;
    private GameStatus status;
    private Player winner;
    private GameOverReason gameOverReason;
    private int humanScore;
    private int botScore;
    private long elapsedMillis;
    private long simulatedSteps;
    private long totalStepNanos;

    public SequentialGame(BoardConf conf) {
        board = new Board();
        board.init(conf);
        status = GameStatus.RUNNING;
    }

    public synchronized boolean shootHuman(V2d velocity) {
        return shoot(Player.HUMAN, velocity);
    }

    public synchronized boolean shootBot() {
        return shoot(Player.BOT, chooseBotShot());
    }

    public synchronized V2d previewBotShot() {
        if (!canBotShoot()) {
            return new V2d(0, 0);
        }
        return chooseBotShot();
    }

    public synchronized boolean canHumanShoot() {
        return canShoot(Player.HUMAN);
    }

    public synchronized boolean canBotShoot() {
        return canShoot(Player.BOT);
    }

    public synchronized boolean shoot(Player player, V2d velocity) {
        if (!canShoot(player) || velocity.abs() < MIN_SHOT_SPEED) {
            return false;
        }
        board.kick(player, velocity);
        updateRunningStatus();
        return true;
    }

    public synchronized void step(long dtMillis) {
        if (dtMillis < 0) {
            throw new IllegalArgumentException("dtMillis must be >= 0");
        }
        if (status == GameStatus.FINISHED) {
            return;
        }

        long start = System.nanoTime();
        board.updateState(dtMillis);
        totalStepNanos += System.nanoTime() - start;
        elapsedMillis += dtMillis;
        simulatedSteps++;

        humanScore += board.consumePendingScoredSmallBalls(Player.HUMAN);
        botScore += board.consumePendingScoredSmallBalls(Player.BOT);

        if (board.isPlayerBallPocketed()) {
            finish(Player.BOT, GameOverReason.HUMAN_CUE_BALL_POCKETED);
            return;
        }
        if (board.isBotBallPocketed()) {
            finish(Player.HUMAN, GameOverReason.BOT_CUE_BALL_POCKETED);
            return;
        }
        if (board.getBalls().isEmpty()) {
            finishByScore();
            return;
        }
        updateRunningStatus();
    }

    public synchronized Board board() {
        return board;
    }

    public synchronized GameSnapshot snapshot() {
        return new GameSnapshot(
                humanScore,
                botScore,
                status,
                winner,
                gameOverReason,
                canHumanShoot(),
                canBotShoot(),
                elapsedMillis,
                simulatedSteps,
                averageStepMillis());
    }

    private boolean canShoot(Player player) {
        return status != GameStatus.FINISHED && board.canKick(player);
    }

    private void updateRunningStatus() {
        status = board.areBallsMoving() ? GameStatus.BALLS_MOVING : GameStatus.RUNNING;
    }

    private V2d chooseBotShot() {
        var botBall = board.getBotBall();
        var balls = board.getBalls();
        if (botBall == null || balls.isEmpty()) {
            return FALLBACK_BOT_SHOT;
        }
        var target = balls.get(0).pos();
        return target.sub(botBall.pos()).getNormalized().mul(BOT_SHOT_SPEED);
    }

    private void finish(Player winner, GameOverReason reason) {
        this.winner = winner;
        this.gameOverReason = reason;
        status = GameStatus.FINISHED;
    }

    private void finishByScore() {
        if (humanScore > botScore) {
            finish(Player.HUMAN, GameOverReason.SMALL_BALLS_CLEARED);
        } else if (botScore > humanScore) {
            finish(Player.BOT, GameOverReason.SMALL_BALLS_CLEARED);
        } else {
            finish(null, GameOverReason.SMALL_BALLS_CLEARED);
        }
    }

    private double averageStepMillis() {
        if (simulatedSteps == 0) {
            return 0.0;
        }
        return totalStepNanos / 1_000_000.0 / simulatedSteps;
    }
}
