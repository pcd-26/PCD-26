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
    private static final long FRAME_SLEEP_MILLIS = 4;
    private static final double BOT_PREVIEW_SCALE = 0.35;

    public static void main(String[] args) {
        // The sequential launcher keeps game rules, bot thinking, and rendering
        // in the same loop. This makes it the simplest baseline to compare
        // against the concurrent launchers.
        var gameRef = new AtomicReference<>(newGame());
        var restartRequested = new AtomicBoolean(false);
        var viewModel = new ViewModel();
        var view = new View(
                viewModel,
                VIEW_WIDTH,
                VIEW_HEIGHT,
                velocity -> gameRef.get().shootHuman(velocity), // Human shot.
                () -> restartRequested.set(true), // Restart request.
                () -> gameRef.get().canHumanShoot(), // Human aiming gate.
                () -> viewModel.clearShotPreview(Player.HUMAN)); // Clear human preview.

        long startTime = System.currentTimeMillis();
        long lastUpdateTime = startTime;
        long botAimStartedAt = 0;
        int renderedFrames = 0;

        while (true) {
            long now = System.currentTimeMillis();
            if (restartRequested.getAndSet(false)) {
                gameRef.set(newGame());
                viewModel.clearShotPreview();
                startTime = now;
                lastUpdateTime = now;
                botAimStartedAt = 0;
                renderedFrames = 0;
            }

            var game = gameRef.get();
            long elapsedMillis = Math.max(PhysicsDefaults.FIXED_STEP_MILLIS, now - lastUpdateTime);
            lastUpdateTime = now;

            advanceGame(game, elapsedMillis);

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
