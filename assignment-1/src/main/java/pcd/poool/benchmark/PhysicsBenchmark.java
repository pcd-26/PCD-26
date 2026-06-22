package pcd.poool.benchmark;

import java.util.Locale;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.sequential.PhysicsEngine;
import pcd.poool.model.physics.config.MassiveBoardConf;

/**
 * Small standalone benchmark entry point for the sequential physics engine.
 */
public class PhysicsBenchmark {

    /**
     * Utility class; not meant to be instantiated.
     */
    private PhysicsBenchmark() {
    }

    /**
     * Runs the benchmark.
     *
     * @param args optional first argument: number of physics steps
     */
    public static void main(String[] args) {
        var config = BenchmarkConfig.physicsBenchmarkDefaults();
        if (args.length > 0) {
            config = config.withSteps(Integer.parseInt(args[0]));
        }
        var board = new Board(new PhysicsEngine());
        board.init(new MassiveBoardConf());

        long start = System.nanoTime();
        for (int i = 0; i < config.steps(); i++) {
            board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
        }
        long elapsed = System.nanoTime() - start;

        double elapsedMillis = elapsed / 1_000_000.0;
        double avgStepMillis = elapsedMillis / config.steps();
        System.out.printf(Locale.US,
                "config=%s steps=%d balls=%d elapsed_ms=%.3f avg_step_ms=%.6f%n",
                config.toKeyValueString(),
                config.steps(),
                board.getBalls().size(),
                elapsedMillis,
                avgStepMillis);
    }
}
