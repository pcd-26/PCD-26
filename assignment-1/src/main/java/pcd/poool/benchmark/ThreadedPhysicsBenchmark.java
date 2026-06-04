package pcd.poool.benchmark;

import java.util.Locale;
import pcd.poool.model.physics.Board;
import pcd.poool.model.physics.PhysicsDefaults;
import pcd.poool.model.physics.ThreadedPhysicsEngine;
import pcd.poool.model.physics.config.MassiveBoardConf;

/**
 * Standalone benchmark for the platform-threaded physics engine.
 */
public class ThreadedPhysicsBenchmark {

    private static final int DEFAULT_STEPS = 600;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;
    private static final String OUTPUT_FORMAT =
            "steps=%d workers=%d balls=%d elapsed_ms=%.3f avg_step_ms=%.6f%n";

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
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_STEPS;
        int workers = args.length > 1
                ? Integer.parseInt(args[1])
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

        try (var physicsEngine = new ThreadedPhysicsEngine(workers)) {
            var board = new Board(physicsEngine);
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
                    physicsEngine.workerCount(),
                    board.getBalls().size(),
                    elapsedMillis,
                    avgStepMillis);
        }
    }
}
