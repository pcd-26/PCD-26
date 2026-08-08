package pcd.poool;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.config.ThousandBallsBoardConf;
import pcd.poool.runtime.GameRuntime;
import pcd.poool.runtime.RuntimeGameSnapshot;
import pcd.poool.threaded.ThreadedGameRunner;
import pcd.poool.view.board.View;
import pcd.poool.view.board.ViewModel;

/** Playable platform-thread version of Poool. */
public final class ThreadedPoool {

    private static final int VIEW_WIDTH = 1200;
    private static final int VIEW_HEIGHT = 800;
    private static final long FRAME_SLEEP_MILLIS = 4;
    private static final double BOT_PREVIEW_SCALE = 0.35;

    private ThreadedPoool() {
    }

    /** Starts the platform-thread application. */
    public static void main(String[] args) {
        var runtimeRef = new AtomicReference<GameRuntime>(startRuntime());
        var restartRequested = new AtomicBoolean(false);
        var viewModel = new ViewModel();
        var view = new View(
                viewModel,
                VIEW_WIDTH,
                VIEW_HEIGHT,
                velocity -> runtimeRef.get().shootHuman(velocity),
                () -> restartRequested.set(true),
                () -> runtimeRef.get().snapshot().game().humanCanShoot(),
                () -> viewModel.clearShotPreview(Player.HUMAN));

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> runtimeRef.get().close(), "poool-threaded-shutdown"));

        long startTime = System.currentTimeMillis();
        int renderedFrames = 0;
        while (true) {
            long now = System.currentTimeMillis();
            if (restartRequested.getAndSet(false)) {
                runtimeRef.get().close();
                runtimeRef.set(startRuntime());
                viewModel.clearShotPreview();
                startTime = now;
                renderedFrames = 0;
            }

            var snapshot = runtimeRef.get().snapshot();
            int framesPerSecond = framesPerSecond(++renderedFrames, startTime, now);
            copyToViewModel(snapshot, viewModel, framesPerSecond);
            updateBotPreview(snapshot, viewModel);
            view.render();
            sleepFrame();
        }
    }

    private static GameRuntime startRuntime() {
        var runtime = new ThreadedGameRunner(new ThousandBallsBoardConf());
        runtime.start();
        return runtime;
    }

    private static int framesPerSecond(int renderedFrames, long startTime, long now) {
        long elapsed = now - startTime;
        return elapsed <= 0 ? 0 : (int) (renderedFrames * 1000 / elapsed);
    }

    private static void copyToViewModel(
            RuntimeGameSnapshot snapshot,
            ViewModel viewModel,
            int framesPerSecond) {
        viewModel.update(
                snapshot.smallBalls(),
                snapshot.humanBall(),
                snapshot.botBall(),
                snapshot.holes(),
                snapshot.game(),
                framesPerSecond);
    }

    private static void updateBotPreview(RuntimeGameSnapshot snapshot, ViewModel viewModel) {
        if (!snapshot.game().botCanShoot() || snapshot.botBall() == null) {
            viewModel.clearShotPreview(Player.BOT);
            return;
        }
        var impulse = snapshot.botPreviewShot();
        if (impulse.abs() <= 0) {
            return;
        }
        var from = snapshot.botBall().pos();
        viewModel.setShotPreview(
                from,
                from.sum(impulse.mul(BOT_PREVIEW_SCALE)),
                impulse.abs(),
                Player.BOT);
    }

    private static void sleepFrame() {
        try {
            Thread.sleep(FRAME_SLEEP_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
