package pcd.poool;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.config.MassiveBoardConf;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.model.physics.config.ThousandBallsBoardConf;
import pcd.poool.runtime.RuntimeGameSnapshot;
import pcd.poool.taskbased.TaskBasedGameRunner;
import pcd.poool.view.board.View;
import pcd.poool.view.board.ViewModel;

/**
 * Playable task-based entry point for Poool.
 *
 * <p>The user-facing experience matches the threaded launcher, but the
 * simulation is coordinated through {@link TaskBasedGameRunner} and its
 * executor-backed physics engine. The runner owns the game model, while the
 * UI loop only consumes immutable snapshots.
 */
public class TaskBasedPoool {

    private static final int VIEW_WIDTH = 1200;
    private static final int VIEW_HEIGHT = 800;
    private static final long FRAME_SLEEP_MILLIS = 4;
    private static final double BOT_PREVIEW_SCALE = 0.35;
    private static final BoardProfile BOARD_PROFILE = BoardProfile.THOUSAND;

    private TaskBasedPoool() {
    }

    /**
     * Starts the playable task-based game.
     *
     * @param args optional first argument: worker count for the task-based
     *             physics engine
     */
    public static void main(String[] args) {
        var boardProfile = BOARD_PROFILE.createConfiguration();
        var config = taskBasedConfig(args);
        System.out.printf(
                "Starting task-based Poool with %d physics workers (%s board)%n",
                config.physicsWorkerCount(),
                BOARD_PROFILE.name().toLowerCase());

        var runnerRef = new AtomicReference<>(newStartedRunner(boardProfile, config));
        var restartRequested = new AtomicBoolean(false);
        var viewModel = new ViewModel();
        var view = new View(
                viewModel,
                VIEW_WIDTH,
                VIEW_HEIGHT,
                velocity -> runnerRef.get().shootHuman(velocity),
                () -> restartRequested.set(true),
                () -> canStartHumanAiming(runnerRef.get()),
                () -> viewModel.clearShotPreview(Player.HUMAN));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> runnerRef.get().close(), "poool-task-based-shutdown"));

        long startTime = System.currentTimeMillis();
        int renderedFrames = 0;

        while (true) {
            long now = System.currentTimeMillis();
            if (restartRequested.getAndSet(false)) {
                var oldRunner = runnerRef.getAndSet(newStartedRunner(boardProfile, config));
                oldRunner.close();
                viewModel.clearShotPreview();
                startTime = now;
                renderedFrames = 0;
            }

            renderedFrames++;
            int framePerSec = framePerSec(renderedFrames, startTime, now);
            var taskSnapshot = runnerRef.get().snapshot();
            viewModel.update(
                    taskSnapshot.smallBalls(),
                    taskSnapshot.humanBall(),
                    taskSnapshot.botBall(),
                    taskSnapshot.holes(),
                    taskSnapshot.game(),
                    framePerSec);
            updateBotShotPreview(taskSnapshot, viewModel);
            view.render();
            sleepFrame();
        }
    }

    static TaskBasedGameRunner.Config taskBasedConfig(String[] args) {
        int workerCount = parseWorkerCount(args);
        return new TaskBasedGameRunner.Config(
                pcd.poool.model.physics.common.PhysicsDefaults.FIXED_STEP_MILLIS,
                true,
                600,
                workerCount);
    }

    static int parseWorkerCount(String[] args) {
        if (args == null || args.length == 0) {
            return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        }
        int workers = Integer.parseInt(args[0]);
        if (workers < 1) {
            throw new IllegalArgumentException("worker count must be >= 1");
        }
        return workers;
    }

    private static TaskBasedGameRunner newStartedRunner(BoardConf boardProfile, TaskBasedGameRunner.Config config) {
        var runner = new TaskBasedGameRunner(boardProfile, config);
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

    private static boolean canStartHumanAiming(TaskBasedGameRunner runner) {
        return runner.snapshot().game().humanCanShoot();
    }

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
