package pcd.poool.model.game;

import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.PhysicsStepper;

// Shared gameplay model and match state.
public class GameModel {

    private static final double MIN_SHOT_SPEED = 0.05;
    private static final double BOT_SHOT_SPEED = 1.2;
    private static final V2d FALLBACK_BOT_SHOT = new V2d(0.35, -1.0).getNormalized().mul(BOT_SHOT_SPEED);
    private static final long DEFAULT_COUNTDOWN_MILLIS = 3_000;

    private final long startTimeMillis = System.currentTimeMillis();
    private final StartupCountdown countdown;

    // Checks whether the startup countdown still blocks shots.
    public synchronized boolean isCountdownActive() {
        return countdown.enabled()
                && (System.currentTimeMillis() - startTimeMillis < countdown.durationMillis());
    }

    private final Board board;
    private GameStatus status;
    private Player winner;
    private GameOverReason gameOverReason;
    private int humanScore;
    private int botScore;
    private long simulatedElapsedMillis;
    private long stepCount;
    private long accumulatedStepNanos;

    // Creates a new game with the default physics engine.
    public GameModel(BoardConf conf) {
        this(conf, null);
    }

    // Creates a new game with a custom physics stepper and the default countdown policy.
    public GameModel(BoardConf conf, PhysicsStepper physicsStepper) {
        this(conf, physicsStepper, StartupCountdown.enabledDefault());
    }

    // Creates a new game with explicit physics and countdown settings.
    public GameModel(BoardConf conf, PhysicsStepper physicsStepper, StartupCountdown startupCountdown) {
        board = physicsStepper == null ? new Board() : new Board(physicsStepper);
        board.init(conf);
        if (startupCountdown == null) {
            throw new IllegalArgumentException("startupCountdown must not be null");
        }
        this.countdown = startupCountdown;
        status = GameStatus.RUNNING_STILL;
    }

    // Attempts to shoot the human cue ball.
    public synchronized boolean shootHuman(V2d velocity) {
        return shoot(Player.HUMAN, velocity);
    }

    // Attempts to shoot the bot cue ball using the deterministic bot policy.
    public synchronized boolean shootBot() {
        return shoot(Player.BOT, computeBotShot());
    }

    // Computes the bot shot without mutating the game state.
    public synchronized V2d previewBotShot() {
        if (!canBotShoot()) {
            return new V2d(0, 0);
        }
        return computeBotShot();
    }

    // Checks whether the human cue ball can be shot now.
    public synchronized boolean canHumanShoot() {
        return canShoot(Player.HUMAN);
    }

    // Checks whether the bot cue ball can be shot now.
    public synchronized boolean canBotShoot() {
        return canShoot(Player.BOT);
    }

    // Attempts to shoot the selected player's cue ball.
    public synchronized boolean shoot(Player player, V2d velocity) {
        if (!canShoot(player) || velocity.abs() < MIN_SHOT_SPEED) {
            return false;
        }
        board.kick(player, velocity);
        refreshStatusFromBoard();
        return true;
    }

    // Advances the logical game state and the underlying board.
    public synchronized void step(long dtMillis) {
        if (dtMillis < 0) {
            throw new IllegalArgumentException("dtMillis must be >= 0");
        }
        if (status == GameStatus.FINISHED) {
            return;
        }

        long start = System.nanoTime();
        board.updateState(dtMillis);
        accumulatedStepNanos += System.nanoTime() - start;

        // Track simulated time separately from wall-clock time.
        simulatedElapsedMillis += dtMillis;
        stepCount++;

        // Consume score events after the physics step has settled.
        humanScore += board.consumePendingScoredSmallBalls(Player.HUMAN);
        botScore += board.consumePendingScoredSmallBalls(Player.BOT);

        // Cue-ball pocketing ends the match immediately.
        if (board.isPlayerBallPocketed()) {
            finishGame(Player.BOT, GameOverReason.HUMAN_CUE_BALL_POCKETED);
            return;
        }
        if (board.isBotBallPocketed()) {
            finishGame(Player.HUMAN, GameOverReason.BOT_CUE_BALL_POCKETED);
            return;
        }
        // If all small balls are gone, the winner is decided by score.
        if (board.getBalls().isEmpty()) {
            finishGameByScore();
            return;
        }
        refreshStatusFromBoard();
    }

    // Exposes the owned board for rendering and physics tests.
    public synchronized Board board() {
        return board;
    }

    // Creates an immutable snapshot of the current game state.
    public synchronized GameSnapshot snapshot() {
        return new GameSnapshot(
                humanScore,
                botScore,
                status,
                winner,
                gameOverReason,
                canHumanShoot(),
                canBotShoot(),
                simulatedElapsedMillis,
                stepCount,
                averageStepMillis());
    }

    private boolean canShoot(Player player) {
        // The countdown has priority over every other shooting rule.
        if (isCountdownActive()) {
            return false;
        }
        return status != GameStatus.FINISHED && board.canKick(player);
    }

    private void refreshStatusFromBoard() {
        status = board.areBallsMoving() ? GameStatus.BALLS_MOVING : GameStatus.RUNNING_STILL;
    }

    private V2d computeBotShot() {
        var botBall = board.getBotBall();
        var balls = board.getBalls();
        if (botBall == null || balls.isEmpty()) {
            return FALLBACK_BOT_SHOT;
        }
        var target = balls.get(0).pos();
        return target.sub(botBall.pos()).getNormalized().mul(BOT_SHOT_SPEED);
    }

    private void finishGame(Player winner, GameOverReason reason) {
        this.winner = winner;
        this.gameOverReason = reason;
        status = GameStatus.FINISHED;
    }

    private void finishGameByScore() {
        if (humanScore > botScore) {
            finishGame(Player.HUMAN, GameOverReason.SMALL_BALLS_CLEARED);
        } else if (botScore > humanScore) {
            finishGame(Player.BOT, GameOverReason.SMALL_BALLS_CLEARED);
        } else {
            finishGame(null, GameOverReason.SMALL_BALLS_CLEARED);
        }
    }

    private double averageStepMillis() {
        if (stepCount == 0) {
            return 0.0;
        }
        return accumulatedStepNanos / 1_000_000.0 / stepCount;
    }

    // Startup countdown policy for a game instance.
    public record StartupCountdown(boolean enabled, long durationMillis) {

        // Validates the countdown configuration.
        public StartupCountdown {
            if (durationMillis < 0) {
                throw new IllegalArgumentException("durationMillis must be >= 0");
            }
        }

        // Creates the default enabled countdown.
        public static StartupCountdown enabledDefault() {
            return new StartupCountdown(true, DEFAULT_COUNTDOWN_MILLIS);
        }

        // Creates a disabled countdown.
        public static StartupCountdown disabled() {
            return new StartupCountdown(false, 0);
        }
    }
}
