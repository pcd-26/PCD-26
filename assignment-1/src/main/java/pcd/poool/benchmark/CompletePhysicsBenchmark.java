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

    private CompletePhysicsBenchmark() {
    }

    /**
     * Runs the complete benchmark suite.
     *
     * @param args optional arguments: number of measured steps, warmup steps,
     *             and repeat count
     */
    public static void main(String[] args) {
        var config = BenchmarkConfig.completeComparisonDefaults();
        if (args.length > 0) {
            config = config.withSteps(Integer.parseInt(args[0]));
        }
        if (args.length > 1) {
            config = config.withWarmupRuns(Integer.parseInt(args[1]));
        }
        if (args.length > 2) {
            config = config.withMeasuredRuns(Integer.parseInt(args[2]));
        }

        for (var scenario : PhysicsBenchmarkSupport.scenarios()) {
            PhysicsBenchmarkSupport.printCompleteComparison(
                    "complete-physics",
                    scenario,
                    config);
        }
    }
}
