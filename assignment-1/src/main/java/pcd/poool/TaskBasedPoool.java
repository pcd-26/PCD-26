package pcd.poool;

import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.config.ThousandBallsBoardConf;
import pcd.poool.taskbased.TaskBasedGameRunner;

/** Playable Executor Framework version of Poool. */
public final class TaskBasedPoool {

    private static final long BOT_THINK_TIME_MILLIS = 600;

    private TaskBasedPoool() {
    }

    /** Starts the task-based application. The first argument may select the worker count. */
    public static void main(String[] args) {
        var config = taskBasedConfig(args);
        System.out.printf(
                "Starting task-based Poool with %d physics workers (thousand board)%n",
                config.physicsWorkerCount());
        PooolApplication.run(
                () -> new TaskBasedGameRunner(new ThousandBallsBoardConf(), config),
                "poool-task-based-shutdown");
    }

    static TaskBasedGameRunner.Config taskBasedConfig(String[] args) {
        return new TaskBasedGameRunner.Config(
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
}
