package pcd.poool;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.config.MassiveBoardConf;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.model.physics.config.ThousandBallsBoardConf;
import pcd.poool.runtime.RuntimeGameSnapshot;
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
    private static final long FRAME_SLEEP_MILLIS = 4; // Small pause between frames so the render loop does not spin at full speed.
    private static final double BOT_PREVIEW_SCALE = 0.35; // Shrinks the bot preview arrow to keep the hint readable on screen.
    private static final BoardProfile BOARD_PROFILE = BoardProfile.THOUSAND;

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
        var boardProfile = BOARD_PROFILE.createConfiguration();
        var runnerRef = new AtomicReference<>(newStartedRunner(boardProfile));
        var restartRequested = new AtomicBoolean(false);
        var viewModel = new ViewModel();
        var view = new View(
                viewModel,
                VIEW_WIDTH,
                VIEW_HEIGHT,
                velocity -> runnerRef.get().shootHuman(velocity), // Callback for submitting a human shot to the runner.
                () -> restartRequested.set(true), // Callback that marks the game for restart.
                () -> canStartHumanAiming(runnerRef.get()), // Callback that checks if the human may start aiming.
                () -> viewModel.clearShotPreview(Player.HUMAN)); // Callback that clears the human shot preview.

        Runtime.getRuntime().addShutdownHook(new Thread(() -> runnerRef.get().close(), "poool-threaded-shutdown"));

        long startTime = System.currentTimeMillis();
        int renderedFrames = 0;

        // UI loop: reads immutable snapshots, updates the ViewModel, and renders.
        while (true) {
            long now = System.currentTimeMillis();
            // Recreate the runner if the user requested a restart.
            if (restartRequested.getAndSet(false)) {
                var oldRunner = runnerRef.getAndSet(newStartedRunner(boardProfile));
                oldRunner.close();
                viewModel.clearShotPreview();
                startTime = now;
                renderedFrames = 0;
            }

            // Read the latest immutable runtime state and project it into the ViewModel.
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
            updateBotShotPreview(threadedSnapshot, viewModel);
            // Draw the current frame and keep the UI loop responsive.
            view.render();
            sleepFrame();
        }
    }

    /**
     * Instantiates and starts a new ThreadedGameRunner with the given board configuration.
     *
     * @param boardProfile the initial layout and setup of the board
     * @return the started ThreadedGameRunner instance
     */
    private static ThreadedGameRunner newStartedRunner(BoardConf boardProfile) {
        var runner = new ThreadedGameRunner(boardProfile);
        runner.start();
        return runner;
    }

    private enum BoardProfile {
        STANDARD {
            @Override
            BoardConf createConfiguration() {
                return new StandardGameBoardConf();
            }
        },
        THOUSAND {
            @Override
            BoardConf createConfiguration() {
                return new ThousandBallsBoardConf();
            }
        },
        MASSIVE {
            @Override
            BoardConf createConfiguration() {
                return new MassiveBoardConf();
            }
        };

        abstract BoardConf createConfiguration();
    }

    /**
     * Grants human aiming while the latest game snapshot reports that the human
     * cue ball is stopped and therefore eligible for a new shot.
     */
    private static boolean canStartHumanAiming(ThreadedGameRunner runner) {
        return runner.snapshot().game().humanCanShoot();
    }

    /**
     * Projects the bot shot preview stored in the immutable runtime snapshot
     * into the shared view model without giving the bot direct access to Swing
     * or mutable game entities.
     */
    private static void updateBotShotPreview(RuntimeGameSnapshot snapshot, ViewModel viewModel) {
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

    /**
     * Computes the current frames per second (FPS) rate.
     *
     * @param renderedFrames total frames rendered during the run
     * @param startTime the start time of the run in milliseconds
     * @param now the current system time in milliseconds
     * @return the calculated frames per second as an integer
     */
    private static int framePerSec(int renderedFrames, long startTime, long now) {
        long elapsed = now - startTime;
        if (elapsed <= 0) {
            return 0;
        }
        return (int) (renderedFrames * 1000 / elapsed);
    }

    /**
     * Puts the current thread to sleep for a configured frame duration to cap frame rate and yield CPU.
     */
    private static void sleepFrame() {
        try {
            Thread.sleep(FRAME_SLEEP_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
