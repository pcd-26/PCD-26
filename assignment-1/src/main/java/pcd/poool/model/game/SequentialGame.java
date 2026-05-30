package pcd.poool.model.game;

import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.Board;
import pcd.poool.model.physics.BoardConf;

/**
 * Single-threaded gameplay coordinator for the playable sequential baseline.
 *
 * <p>The class owns the game rules above the passive physics engine: score
 * accounting, cue-ball availability, end-game conditions, and baseline timing
 * metrics. Human and bot readiness are independent, matching the assignment
 * rule that players act asynchronously; the sequential runner still invokes
 * this object from one loop, so model mutation remains serialized.
 */
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

    /**
     * Creates a new game from the given board configuration.
     *
     * @param conf initial board layout, cue balls, small balls, bounds, and holes
     */
    public SequentialGame(BoardConf conf) {
        board = new Board();
        board.init(conf);
        status = GameStatus.RUNNING;
    }

    /**
     * Attempts to kick the human cue ball.
     *
     * @param velocity impulse-like velocity assigned to the human cue ball
     * @return {@code true} when the shot was accepted
     */
    public synchronized boolean shootHuman(V2d velocity) {
        return shoot(Player.HUMAN, velocity);
    }

    /**
     * Attempts to kick the bot cue ball using the deterministic bot policy.
     *
     * @return {@code true} when the bot cue ball was available and the shot was accepted
     */
    public synchronized boolean shootBot() {
        return shoot(Player.BOT, chooseBotShot());
    }

    /**
     * Computes the bot shot without mutating the game.
     *
     * <p>The runner uses this for the red preview vector before the bot actually
     * kicks. A zero vector means the bot cannot currently shoot.
     */
    public synchronized V2d previewBotShot() {
        if (!canBotShoot()) {
            return new V2d(0, 0);
        }
        return chooseBotShot();
    }

    /**
     * @return whether the human cue ball is present, stopped, and the game is not finished
     */
    public synchronized boolean canHumanShoot() {
        return canShoot(Player.HUMAN);
    }

    /**
     * @return whether the bot cue ball is present, stopped, and the game is not finished
     */
    public synchronized boolean canBotShoot() {
        return canShoot(Player.BOT);
    }

    /**
     * Attempts to kick one player's cue ball.
     *
     * <p>This is not turn-based: each player is accepted independently when
     * their own cue ball is stopped. Very small impulses are rejected so clicks
     * near the cue ball do not create accidental shots.
     *
     * @param player cue-ball owner
     * @param velocity impulse-like velocity to assign
     * @return {@code true} when the shot was accepted
     */
    public synchronized boolean shoot(Player player, V2d velocity) {
        if (!canShoot(player) || velocity.abs() < MIN_SHOT_SPEED) {
            return false;
        }
        board.kick(player, velocity);
        updateRunningStatus();
        return true;
    }

    /**
     * Advances the game and physics state by the elapsed time.
     *
     * <p>The method also consumes scoring events collected by the board,
     * detects cue-ball losses, detects completion after all small balls are
     * pocketed, and records baseline step timing.
     *
     * @param dtMillis elapsed time in milliseconds
     */
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

    /**
     * Exposes the owned board for rendering and benchmarks.
     *
     * <p>Callers must keep model mutation through this game facade unless they
     * are implementing low-level physics tests.
     */
    public synchronized Board board() {
        return board;
    }

    /**
     * @return immutable snapshot of scores, lifecycle state, readiness, and metrics
     */
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
