package pcd.poool.benchmark;

/**
 * Standalone benchmark comparing task-based and threaded physics.
 */
public class TaskVsThreadedPhysicsBenchmark {

    private TaskVsThreadedPhysicsBenchmark() {
    }

    /**
     * Runs the comparison benchmark.
     *
     * @param args optional arguments: number of physics steps, warmup steps,
     *             and repeat count
     */
    public static void main(String[] args) {
        var config = BenchmarkConfig.taskVsThreadedDefaults();
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
            for (var workers : PhysicsBenchmarkSupport.workerCounts()) {
                PhysicsBenchmarkSupport.printComparison(
                        "task-vs-threaded",
                        scenario,
                        config.withThreads(workers));
            }
        }
    }
}
