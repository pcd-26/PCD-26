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

    private static final int DEFAULT_STEPS = 600;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;
    private static final String OUTPUT_FORMAT = "steps=%d balls=%d elapsed_ms=%.3f avg_step_ms=%.6f%n";

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
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_STEPS;
        var board = new Board(new PhysicsEngine());
        board.init(new MassiveBoardConf());

        long start = System.nanoTime();
        for (int i = 0; i < steps; i++) {
            board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
        }
        long elapsed = System.nanoTime() - start;

        double elapsedMillis = elapsed / NANOS_PER_MILLISECOND;
        double avgStepMillis = elapsedMillis / steps;
        System.out.printf(Locale.US, OUTPUT_FORMAT,
                steps,
                board.getBalls().size(),
                elapsedMillis,
                avgStepMillis);
    }
}
