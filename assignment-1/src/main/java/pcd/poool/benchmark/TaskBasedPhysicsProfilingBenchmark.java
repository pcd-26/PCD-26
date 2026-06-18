package pcd.poool.benchmark;

/**
 * Profiling-oriented benchmark for the task-based physics pipeline.
 *
 * <p>The current physics engine does not expose internal phase timings, so
 * this benchmark focuses on repeatable wall-clock measurements across board
 * shapes and worker counts.
 */
public class TaskBasedPhysicsProfilingBenchmark {

    private static final int DEFAULT_STEPS = 120;
    private static final int DEFAULT_WARMUP_STEPS = 25;
    private static final int DEFAULT_REPEATS = 5;

    private TaskBasedPhysicsProfilingBenchmark() {
    }

    /**
     * Runs the profiling benchmark.
     *
     * @param args optional arguments: number of physics steps, warmup steps,
     *             and repeat count
     */
    public static void main(String[] args) {
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_STEPS;
        int warmupSteps = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_WARMUP_STEPS;
        int repeats = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_REPEATS;

        for (var scenario : PhysicsBenchmarkSupport.scenarios()) {
            for (var workers : PhysicsBenchmarkSupport.workerCounts()) {
                PhysicsBenchmarkSupport.printTaskProfiling(
                        "task-profiling",
                        scenario,
                        workers,
                        steps,
                        warmupSteps,
                        repeats);
            }
        }
    }
}
