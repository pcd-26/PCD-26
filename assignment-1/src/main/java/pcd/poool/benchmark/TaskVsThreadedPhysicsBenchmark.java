package pcd.poool.benchmark;

/**
 * Standalone benchmark comparing task-based and threaded physics.
 */
public class TaskVsThreadedPhysicsBenchmark {

    private static final int DEFAULT_STEPS = 600;
    private static final int DEFAULT_WARMUP_STEPS = 50;
    private static final int DEFAULT_REPEATS = 5;

    private TaskVsThreadedPhysicsBenchmark() {
    }

    /**
     * Runs the comparison benchmark.
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
                PhysicsBenchmarkSupport.printComparison(
                        "task-vs-threaded",
                        scenario,
                        workers,
                        steps,
                        warmupSteps,
                        repeats);
            }
        }
    }
}
