package pcd.poool;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.threaded.ThreadedGameRunner;
import pcd.poool.view.board.View;
import pcd.poool.view.board.ViewModel;

/**
 * Playable platform-thread entry point for Poool.
 *
 * <p>The GUI loop only renders immutable snapshots. Physics and game-rule
 * mutations are owned by {@link ThreadedGameRunner}'s controller platform
 * thread, while the bot can run as a separate active component.
 */
public class ThreadedPoool {

    private static final int VIEW_WIDTH = 1200;
    private static final int VIEW_HEIGHT = 800;
    private static final long FRAME_SLEEP_MILLIS = 4;
    private static final double BOT_PREVIEW_SCALE = 0.35;

    /**
     * Utility class; not meant to be instantiated.
     */
    private ThreadedPoool() {
    }

    /**
     * Starts the playable platform-thread game.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        var runnerRef = new AtomicReference<>(newStartedRunner());
        var restartRequested = new AtomicBoolean(false);
        var aimingOwner = new AtomicReference<Player>();
        var viewModel = new ViewModel();
        var view = new View(
                viewModel,
                VIEW_WIDTH,
                VIEW_HEIGHT,
                velocity -> runnerRef.get().shootHuman(velocity),
                () -> restartRequested.set(true),
                () -> canStartHumanAiming(runnerRef.get(), aimingOwner),
                () -> SequentialPoool.stopAiming(aimingOwner, Player.HUMAN));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> runnerRef.get().close(), "poool-threaded-shutdown"));

        long startTime = System.currentTimeMillis();
        int renderedFrames = 0;

        while (true) {
            long now = System.currentTimeMillis();
            if (restartRequested.getAndSet(false)) {
                var oldRunner = runnerRef.getAndSet(newStartedRunner());
                oldRunner.close();
                aimingOwner.set(null);
                viewModel.clearShotPreview();
                startTime = now;
                renderedFrames = 0;
            }

            renderedFrames++;
            int framePerSec = framePerSec(renderedFrames, startTime, now);
            var threadedSnapshot = runnerRef.get().snapshot();
            viewModel.update(
                    threadedSnapshot.smallBalls(),
                    threadedSnapshot.humanBall(),
                    threadedSnapshot.botBall(),
                    threadedSnapshot.holes(),
                    threadedSnapshot.game(),
                    framePerSec);
            updateBotShotPreview(threadedSnapshot, viewModel, aimingOwner.get());
            view.render();
            sleepFrame();
        }
    }

    private static ThreadedGameRunner newStartedRunner() {
        var runner = new ThreadedGameRunner(new StandardGameBoardConf());
        runner.start();
        return runner;
    }

    /**
     * Grants human aiming only while the latest game snapshot reports that the
     * human cue ball is stopped and therefore eligible for a new shot.
     */
    private static boolean canStartHumanAiming(
            ThreadedGameRunner runner,
            AtomicReference<Player> aimingOwner) {
        return runner.snapshot().game().humanCanShoot()
                && SequentialPoool.tryStartAiming(aimingOwner, Player.HUMAN);
    }

    /**
     * Projects the bot shot preview stored in the immutable threaded snapshot
     * into the shared view model without giving the bot direct access to Swing
     * or mutable game entities.
     */
    private static void updateBotShotPreview(
            pcd.poool.threaded.ThreadedGameSnapshot snapshot,
            ViewModel viewModel,
            Player aimingOwner) {
        var currentPreview = viewModel.getShotPreview();
        if (aimingOwner == Player.HUMAN) {
            if (currentPreview != null && currentPreview.player() == Player.BOT) {
                viewModel.clearShotPreview();
            }
            return;
        }
        if (!snapshot.game().botCanShoot() || snapshot.botBall() == null) {
            if (currentPreview != null && currentPreview.player() == Player.BOT) {
                viewModel.clearShotPreview();
            }
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

    private static int framePerSec(int renderedFrames, long startTime, long now) {
        long elapsed = now - startTime;
        if (elapsed <= 0) {
            return 0;
        }
        return (int) (renderedFrames * 1000 / elapsed);
    }

    private static void sleepFrame() {
        try {
            Thread.sleep(FRAME_SLEEP_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
