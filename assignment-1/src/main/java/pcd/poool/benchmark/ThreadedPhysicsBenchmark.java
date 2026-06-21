package pcd.poool.benchmark;

import java.util.Locale;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;
import pcd.poool.model.physics.config.MassiveBoardConf;

/**
 * Standalone benchmark for the platform-threaded physics engine.
 */
public class ThreadedPhysicsBenchmark {

    /**
     * Utility class; not meant to be instantiated.
     */
    private ThreadedPhysicsBenchmark() {
    }

    /**
     * Runs the benchmark.
     *
     * @param args optional arguments: number of physics steps, worker count
     */
    public static void main(String[] args) {
        var config = BenchmarkConfig.threadedPhysicsDefaults();
        if (args.length > 0) {
            config = config.withSteps(Integer.parseInt(args[0]));
        }
        if (args.length > 1) {
            config = config.withThreads(Integer.parseInt(args[1]));
        }

        try (var physicsEngine = new ThreadedPhysicsEngine(config.effectiveThreads())) {
            var board = new Board(physicsEngine);
            board.init(new MassiveBoardConf());

            long start = System.nanoTime();
            for (int i = 0; i < config.steps(); i++) {
                board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }
            long elapsed = System.nanoTime() - start;

            double elapsedMillis = elapsed / 1_000_000.0;
            double avgStepMillis = elapsedMillis / config.steps();
            System.out.printf(Locale.US,
                    "config=%s steps=%d workers=%d balls=%d elapsed_ms=%.3f avg_step_ms=%.6f%n",
                    config.toKeyValueString(),
                    config.steps(),
                    physicsEngine.workerCount(),
                    board.getBalls().size(),
                    elapsedMillis,
                    avgStepMillis);
        }
    }
}
