package pcd.poool;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import pcd.poool.model.game.Player;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.model.physics.sequential.SequentialPhysicsEngine;
import pcd.poool.view.board.View;
import pcd.poool.view.board.ViewModel;

public class SequentialPoool {

    private static final int VIEW_WIDTH = 1200;
    private static final int VIEW_HEIGHT = 800;
    private static final long BOT_THINK_TIME_MILLIS = 600;
    private static final long FRAME_SLEEP_MILLIS = 4; // Limits the render loop update rate.
    private static final double BOT_PREVIEW_SCALE = 0.35; // Scales the bot preview arrow.

    public static void main(String[] args) {
        var gameRef = new AtomicReference<>(newGame());
        var restartRequested = new AtomicBoolean(false);
        var viewModel = new ViewModel();
        var view = new View(
                viewModel,
                VIEW_WIDTH,
                VIEW_HEIGHT,
                velocity -> gameRef.get().shootHuman(velocity), // Human shot callback.
                () -> restartRequested.set(true), // Restart callback.
                () -> gameRef.get().canHumanShoot(), // Human aiming gate.
                () -> viewModel.clearShotPreview(Player.HUMAN)); // Clears the human shot preview.

        long startTime = System.currentTimeMillis();
        long lastUpdateTime = startTime;
        long botAimStartedAt = 0;
        int renderedFrames = 0;

        // Single game loop: updates gameplay, bot timing, rendering, and restart handling.
        while (true) {
            long now = System.currentTimeMillis();
            // Apply a pending restart request by rebuilding the game and clearing UI state.
            if (restartRequested.getAndSet(false)) {
                gameRef.set(newGame());
                viewModel.clearShotPreview();
                startTime = now;
                lastUpdateTime = now;
                botAimStartedAt = 0;
                renderedFrames = 0;
            }

            var game = gameRef.get(); // Get the current game state to update and render.
            // Advance the simulation using the elapsed real time since the previous frame.
            long elapsedMillis = Math.max(PhysicsDefaults.FIXED_STEP_MILLIS, now - lastUpdateTime);
            lastUpdateTime = now;

            advanceGame(game, elapsedMillis);
            // Track the bot aim delay before firing its shot.
            if (game.canBotShoot()) {
                if (botAimStartedAt == 0) {
                    botAimStartedAt = now;
                } else if (now - botAimStartedAt >= BOT_THINK_TIME_MILLIS) {
                    game.shootBot();
                    viewModel.clearShotPreview(Player.BOT);
                    botAimStartedAt = 0;
                }
            } else {
                viewModel.clearShotPreview(Player.BOT);
                botAimStartedAt = 0;
            }

            // Publish a fresh view state and render the current frame.
            renderedFrames++;
            int currentFramesPerSecond = getCurrentFPS(renderedFrames, startTime, now);
            viewModel.update(game.board(), game.snapshot(), currentFramesPerSecond);
            updateBotShotPreview(game, viewModel, botAimStartedAt > 0);
            view.render();
            sleepFrame();
        }
    }

    private static GameModel newGame() {
        return new GameModel(new StandardGameBoardConf(), new SequentialPhysicsEngine());
    }

    private static void advanceGame(GameModel game, long elapsedMillis) {
        if (!game.snapshot().isFinished()) {
            game.step(elapsedMillis);
        }
    }

    private static int getCurrentFPS(int renderedFrames, long startTime, long now) {
        long elapsed = now - startTime;
        if (elapsed <= 0) {
            return 0;
        }
        return (int) (renderedFrames * 1000 / elapsed);
    }

    private static void updateBotShotPreview(GameModel game, ViewModel viewModel, boolean isBotAiming) {
        if (!game.canBotShoot() || !isBotAiming) {
            return;
        }
        var bot = game.board().getBotBall();
        if (bot == null) {
            return;
        }
        var impulse = game.previewBotShot();
        var target = bot.pos().sum(impulse.mul(BOT_PREVIEW_SCALE));
        viewModel.setShotPreview(bot.pos(), target, impulse.abs(), Player.BOT);
    }

    static boolean isHumanAiming(ViewModel viewModel) {
        var preview = viewModel.getShotPreview(Player.HUMAN);
        return preview != null && preview.player() == Player.HUMAN;
    }

    private static void sleepFrame() {
        try {
            Thread.sleep(FRAME_SLEEP_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
