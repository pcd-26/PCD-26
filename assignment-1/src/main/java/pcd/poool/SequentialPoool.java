package pcd.poool;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import pcd.poool.model.game.Player;
import pcd.poool.model.game.SequentialGame;
import pcd.poool.model.physics.PhysicsDefaults;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.view.board.View;
import pcd.poool.view.board.ViewModel;

public class SequentialPoool {

    private static final int VIEW_WIDTH = 1200;
    private static final int VIEW_HEIGHT = 800;
    private static final long BOT_THINK_TIME_MILLIS = 600;
    private static final long FRAME_SLEEP_MILLIS = 4;
    private static final double BOT_PREVIEW_SCALE = 0.35;

    public static void main(String[] args) {
        var gameRef = new AtomicReference<>(newGame());
        var restartRequested = new AtomicBoolean(false);
        var humanAiming = new AtomicBoolean(false);
        var viewModel = new ViewModel();
        var view = new View(
                viewModel,
                VIEW_WIDTH,
                VIEW_HEIGHT,
                velocity -> gameRef.get().shootHuman(velocity),
                () -> restartRequested.set(true),
                humanAiming::set);

        long startTime = System.currentTimeMillis();
        long lastUpdateTime = startTime;
        long waitingForBotSince = 0;
        int renderedFrames = 0;

        while (true) {
            long now = System.currentTimeMillis();
            if (restartRequested.getAndSet(false)) {
                gameRef.set(newGame());
                humanAiming.set(false);
                viewModel.clearShotPreview();
                startTime = now;
                lastUpdateTime = now;
                waitingForBotSince = 0;
                renderedFrames = 0;
            }

            var game = gameRef.get();
            long elapsed = Math.max(PhysicsDefaults.FIXED_STEP_MILLIS, now - lastUpdateTime);
            lastUpdateTime = now;

            if (!game.snapshot().isFinished()) {
                game.step(elapsed);
            }
            if (humanAiming.get()) {
                waitingForBotSince = 0;
            } else if (game.canBotShoot()) {
                if (waitingForBotSince == 0) {
                    waitingForBotSince = now;
                } else if (now - waitingForBotSince >= BOT_THINK_TIME_MILLIS) {
                    game.shootBot();
                    viewModel.clearShotPreview();
                    waitingForBotSince = 0;
                }
            } else {
                waitingForBotSince = 0;
            }

            renderedFrames++;
            int framePerSec = framePerSec(renderedFrames, startTime, now);
            viewModel.update(game.board(), game.snapshot(), framePerSec);
            updateBotShotPreview(game, viewModel, humanAiming.get());
            view.render();
            sleepFrame();
        }
    }

    private static SequentialGame newGame() {
        return new SequentialGame(new StandardGameBoardConf());
    }

    private static int framePerSec(int renderedFrames, long startTime, long now) {
        long elapsed = now - startTime;
        if (elapsed <= 0) {
            return 0;
        }
        return (int) (renderedFrames * 1000 / elapsed);
    }

    private static void updateBotShotPreview(SequentialGame game, ViewModel viewModel, boolean humanAiming) {
        if (!game.canBotShoot() || humanAiming) {
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
        var preview = viewModel.getShotPreview();
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
