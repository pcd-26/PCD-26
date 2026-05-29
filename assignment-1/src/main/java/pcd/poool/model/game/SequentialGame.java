package pcd.poool.model.game;

import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.Board;
import pcd.poool.model.physics.BoardConf;

public class SequentialGame {

    private static final double MIN_SHOT_SPEED = 0.05;
    private static final double BOT_SHOT_SPEED = 1.2;
    private static final V2d FALLBACK_BOT_SHOT = new V2d(0.35, -1.0).getNormalized().mul(BOT_SHOT_SPEED);

    private final Board board;
    private Player currentPlayer;
    private GameStatus status;
    private Player winner;
    private int humanScore;
    private int botScore;
    private long elapsedMillis;
    private long simulatedSteps;
    private long totalStepNanos;

    public SequentialGame(BoardConf conf) {
        board = new Board();
        board.init(conf);
        currentPlayer = Player.HUMAN;
        status = GameStatus.WAITING_FOR_HUMAN_SHOT;
    }

    public synchronized boolean shootHuman(V2d velocity) {
        return shoot(Player.HUMAN, velocity);
    }

    public synchronized boolean shootBot() {
        return shoot(Player.BOT, chooseBotShot());
    }

    public synchronized boolean shoot(Player player, V2d velocity) {
        if (status == GameStatus.FINISHED || status == GameStatus.BALLS_MOVING || player != currentPlayer) {
            return false;
        }
        if (velocity.abs() < MIN_SHOT_SPEED) {
            return false;
        }
        board.prepareTurn(player);
        board.kick(player, velocity);
        status = GameStatus.BALLS_MOVING;
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

        if (board.isPlayerBallPocketed()) {
            finish(Player.BOT);
            return;
        }
        if (board.isBotBallPocketed()) {
            finish(Player.HUMAN);
            return;
        }
        if (status == GameStatus.BALLS_MOVING && !board.areBallsMoving()) {
            settleTurn();
        }
        if (board.getBalls().isEmpty()) {
            finishByScore();
        }
    }

    public synchronized Board board() {
        return board;
    }

    public synchronized GameSnapshot snapshot() {
        return new GameSnapshot(
                humanScore,
                botScore,
                currentPlayer,
                status,
                winner,
                elapsedMillis,
                simulatedSteps,
                averageStepMillis());
    }

    private void settleTurn() {
        int scored = board.consumePendingScoredSmallBalls();
        if (currentPlayer == Player.HUMAN) {
            humanScore += scored;
        } else {
            botScore += scored;
        }
        currentPlayer = currentPlayer.opponent();
        status = currentPlayer == Player.HUMAN
                ? GameStatus.WAITING_FOR_HUMAN_SHOT
                : GameStatus.WAITING_FOR_BOT_SHOT;
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

    private void finish(Player winner) {
        this.winner = winner;
        status = GameStatus.FINISHED;
    }

    private void finishByScore() {
        if (humanScore > botScore) {
            finish(Player.HUMAN);
        } else if (botScore > humanScore) {
            finish(Player.BOT);
        } else {
            finish(null);
        }
    }

    private double averageStepMillis() {
        if (simulatedSteps == 0) {
            return 0.0;
        }
        return totalStepNanos / 1_000_000.0 / simulatedSteps;
    }
}
