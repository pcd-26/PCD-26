package pcd.poool;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.config.ThousandBallsBoardConf;
import pcd.poool.runtime.GameRuntime;
import pcd.poool.runtime.GameRuntimeConfig;
import pcd.poool.runtime.RuntimeGameSnapshot;
import pcd.poool.taskbased.TaskBasedGameRunner;
import pcd.poool.view.board.View;
import pcd.poool.view.board.ViewModel;

/**
 * Playable task-based version of Poool.
 *
 * <p>This launcher uses the executor-based runtime, so the game loop is still
 * the same UI-facing shell but the physics step is delegated to a task-based
 * worker pool. The rest of the launcher is intentionally close to the
 * threaded one, which makes the comparison between execution strategies easy
 * to explain.
 */
public final class TaskBasedPoool {

    private static final long BOT_THINK_TIME_MILLIS = 600;
    private static final int VIEW_WIDTH = 1200;
    private static final int VIEW_HEIGHT = 800;
    private static final long FRAME_SLEEP_MILLIS = 4;
    private static final double BOT_PREVIEW_SCALE = 0.35;

    private TaskBasedPoool() {
    }

    public static void main(String[] args) {
        // The worker count is configurable so the same launcher can be used for
        // both normal play and scaling experiments.
        var config = taskBasedConfig(args);
        System.out.printf(
                "Starting task-based Poool with %d physics workers (thousand board)%n",
                config.physicsWorkerCount());

        var runtimeRef = new AtomicReference<GameRuntime>(startRuntime(config));
        var restartRequested = new AtomicBoolean(false);
        var viewModel = new ViewModel();
        var view = new View(
                viewModel,
                VIEW_WIDTH,
                VIEW_HEIGHT,
                velocity -> runtimeRef.get().shootHuman(velocity), // Human shot.
                () -> restartRequested.set(true), // Restart request.
                () -> runtimeRef.get().snapshot().game().humanCanShoot(), // Human aiming gate.
                () -> viewModel.clearShotPreview(Player.HUMAN)); // Clear human preview.

        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> runtimeRef.get().close(), "poool-task-based-shutdown"));

        long startTime = System.currentTimeMillis();
        int renderedFrames = 0;
        while (true) {
            long now = System.currentTimeMillis();
            if (restartRequested.getAndSet(false)) {
                runtimeRef.get().close();
                runtimeRef.set(startRuntime(config));
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

    static GameRuntimeConfig taskBasedConfig(String[] args) {
        return new GameRuntimeConfig(
                PhysicsDefaults.FIXED_STEP_MILLIS,
                true,
                BOT_THINK_TIME_MILLIS,
                parseWorkerCount(args));
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

    private static GameRuntime startRuntime(GameRuntimeConfig config) {
        var runtime = new TaskBasedGameRunner(new ThousandBallsBoardConf(), config);
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
