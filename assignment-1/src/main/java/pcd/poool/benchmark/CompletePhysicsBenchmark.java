package pcd.poool.benchmark;

/**
 * Complete benchmark suite comparing the sequential, platform-threaded, and
 * task-based physics engines on the same deterministic workloads.
 *
 * <p>The output is intentionally line-oriented and machine-readable so it can
 * be pasted into the assignment report or filtered by scripts. Each scenario
 * prints one sequential baseline, every threaded/task worker configuration,
 * and a final recommendation line.
 */
public class CompletePhysicsBenchmark {

    private static final int DEFAULT_STEPS = 30;
    private static final int DEFAULT_WARMUP_STEPS = 5;
    private static final int DEFAULT_REPEATS = 2;

    private CompletePhysicsBenchmark() {
    }

    /**
     * Runs the complete benchmark suite.
     *
     * @param args optional arguments: number of measured steps, warmup steps,
     *             and repeat count
     */
    public static void main(String[] args) {
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_STEPS;
        int warmupSteps = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_WARMUP_STEPS;
        int repeats = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_REPEATS;

        for (var scenario : PhysicsBenchmarkSupport.scenarios()) {
            PhysicsBenchmarkSupport.printCompleteComparison(
                    "complete-physics",
                    scenario,
                    steps,
                    warmupSteps,
                    repeats);
        }
    }
}
