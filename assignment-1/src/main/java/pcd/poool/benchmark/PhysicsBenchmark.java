package pcd.poool.benchmark;

import pcd.poool.model.physics.Board;
import pcd.poool.model.physics.config.MassiveBoardConf;

/**
 * Small standalone benchmark entry point for the sequential physics engine.
 */
public class PhysicsBenchmark {

    private static final int DEFAULT_STEPS = 600;
    private static final long STEP_MILLIS = 16;

    public static void main(String[] args) {
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_STEPS;
        var board = new Board();
        board.init(new MassiveBoardConf());

        long start = System.nanoTime();
        for (int i = 0; i < steps; i++) {
            board.updateState(STEP_MILLIS);
        }
        long elapsed = System.nanoTime() - start;

        double elapsedMillis = elapsed / 1_000_000.0;
        double avgStepMillis = elapsedMillis / steps;
        System.out.printf("steps=%d balls=%d elapsed_ms=%.3f avg_step_ms=%.6f%n",
                steps,
                board.getBalls().size(),
                elapsedMillis,
                avgStepMillis);
    }
}
