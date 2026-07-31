package pcd.poool.model.game;

import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.PhysicsStepper;

/**
 * Shared gameplay model used by both sequential and threaded runtimes.
 *
 * <p>The class owns the game rules above the passive physics engine: score
 * accounting, cue-ball availability, end-game conditions, and timing metrics.
 * Human and bot readiness are independent, while callers still serialize
 * mutation through the chosen runtime strategy.</p>
 */
public class GameModel {

    private static final double MIN_SHOT_SPEED = 0.05;
    private static final double BOT_SHOT_SPEED = 1.2;
    private static final V2d FALLBACK_BOT_SHOT = new V2d(0.35, -1.0).getNormalized().mul(BOT_SHOT_SPEED);
    private static final long DEFAULT_COUNTDOWN_MILLIS = 3_000;

    private final long gameStartSystemTime = System.currentTimeMillis();
    private final StartupCountdown startupCountdown;

    /**
     * Checks if the 3-second game start countdown is active.
     *
     * @return true if countdown is active, false otherwise
     */
    public synchronized boolean isCountdownActive() {
        return startupCountdown.enabled()
                && (System.currentTimeMillis() - gameStartSystemTime < startupCountdown.durationMillis());
    }

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
    public GameModel(BoardConf conf) {
        this(conf, null);
    }

    /**
     * Creates a new game from the given board configuration and physics
     * stepping strategy.
     *
     * @param conf initial board layout, cue balls, small balls, bounds, and holes
     * @param physicsStepper physics strategy, or {@code null} for the default
     *        sequential engine
     */
    public GameModel(BoardConf conf, PhysicsStepper physicsStepper) {
        this(conf, physicsStepper, StartupCountdown.enabledDefault());
    }

    /**
     * Creates a new game from the given board configuration, physics strategy,
     * and startup countdown configuration.
     *
     * @param conf initial board layout, cue balls, small balls, bounds, and holes
     * @param physicsStepper physics strategy, or {@code null} for the default
     *        sequential engine
     * @param startupCountdown startup countdown configuration
     */
    public GameModel(BoardConf conf, PhysicsStepper physicsStepper, StartupCountdown startupCountdown) {
        board = physicsStepper == null ? new Board() : new Board(physicsStepper);
        board.init(conf);
        if (startupCountdown == null) {
            throw new IllegalArgumentException("startupCountdown must not be null");
        }
        this.startupCountdown = startupCountdown;
        status = GameStatus.RUNNING_STILL;
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
     *
     * @return bot shot velocity preview
     */
    public synchronized V2d previewBotShot() {
        if (!canBotShoot()) {
            return new V2d(0, 0);
        }
        return chooseBotShot();
    }

    /**
     * Checks human cue-ball readiness.
     *
     * @return whether the human cue ball is present, stopped, and the game is not finished
     */
    public synchronized boolean canHumanShoot() {
        return canShoot(Player.HUMAN);
    }

    /**
     * Checks bot cue-ball readiness.
     *
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
     * pocketed, and records timing metrics.
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
     *
     * @return owned mutable board
     */
    public synchronized Board board() {
        return board;
    }

    /**
     * Creates an immutable snapshot of the current game state.
     *
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
        if (isCountdownActive()) {
            return false;
        }
        return status != GameStatus.FINISHED && board.canKick(player);
    }

    private void updateRunningStatus() {
        status = board.areBallsMoving() ? GameStatus.BALLS_MOVING : GameStatus.RUNNING_STILL;
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

    /**
     * Startup countdown configuration for a game instance.
     *
     * @param enabled whether shots are blocked at startup
     * @param durationMillis countdown duration in milliseconds
     */
    public record StartupCountdown(boolean enabled, long durationMillis) {

        /**
         * Validates the countdown configuration.
         */
        public StartupCountdown {
            if (durationMillis < 0) {
                throw new IllegalArgumentException("durationMillis must be >= 0");
            }
        }

        /**
         * Creates the default gameplay countdown.
         *
         * @return enabled three-second startup countdown
         */
        public static StartupCountdown enabledDefault() {
            return new StartupCountdown(true, DEFAULT_COUNTDOWN_MILLIS);
        }

        /**
         * Creates a disabled startup countdown.
         *
         * @return disabled startup countdown
         */
        public static StartupCountdown disabled() {
            return new StartupCountdown(false, 0);
        }
    }
}
