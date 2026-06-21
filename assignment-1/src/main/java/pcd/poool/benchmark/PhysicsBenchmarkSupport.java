package pcd.poool.benchmark;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.config.MassiveBoardConf;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.model.physics.config.ThousandBallsBoardConf;
import pcd.poool.model.physics.sequential.PhysicsEngine;
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;

final class PhysicsBenchmarkSupport {

    private static final double NANOS_PER_MILLISECOND = 1_000_000.0;

    private PhysicsBenchmarkSupport() {
    }

    static List<Scenario> scenarios() {
        return List.of(
                new Scenario("small", StandardGameBoardConf::new),
                new Scenario("medium", ThousandBallsBoardConf::new),
                new Scenario("high-load", MassiveBoardConf::new));
    }

    static List<Integer> workerCounts() {
        int available = Runtime.getRuntime().availableProcessors();
        Set<Integer> workers = new LinkedHashSet<>();
        workers.add(1);
        workers.add(2);
        workers.add(Math.max(1, available));
        workers.add(Math.max(1, available + 1));
        return new ArrayList<>(workers);
    }

    static BenchmarkSummary runTaskScenario(
            Scenario scenario,
            int workers,
            int steps,
            int warmupSteps,
            int repeats) {
        return summarize(repeats, () -> runTaskOnce(scenario, workers, steps, warmupSteps));
    }

    static BenchmarkSummary runSequentialScenario(
            Scenario scenario,
            int steps,
            int warmupSteps,
            int repeats) {
        return summarize(repeats, () -> runSequentialOnce(scenario, steps, warmupSteps));
    }

    static BenchmarkSummary runThreadedScenario(
            Scenario scenario,
            int workers,
            int steps,
            int warmupSteps,
            int repeats) {
        return summarize(repeats, () -> runThreadedOnce(scenario, workers, steps, warmupSteps));
    }

    static BenchmarkSummary runTaskScenario(Scenario scenario, int steps, int warmupSteps, int repeats) {
        return runTaskScenario(scenario, Math.max(1, Runtime.getRuntime().availableProcessors()), steps, warmupSteps, repeats);
    }

    static void printTaskSummary(String benchmark, Scenario scenario, int workers, int steps, int warmupSteps, int repeats) {
        var summary = runTaskScenario(scenario, workers, steps, warmupSteps, repeats);
        System.out.printf(Locale.US,
                "benchmark=%s engine=task scenario=%s workers=%d steps=%d warmup=%d repeats=%d balls=%d elapsed_ms=%.3f avg_step_ms=%.6f checksum=%d%n",
                benchmark,
                scenario.name(),
                workers,
                steps,
                warmupSteps,
                repeats,
                summary.balls(),
                summary.elapsedMillis(),
                summary.avgStepMillis(),
                summary.checksum());
    }

    static void printSequentialSummary(String benchmark, Scenario scenario, int steps, int warmupSteps, int repeats) {
        var summary = runSequentialScenario(scenario, steps, warmupSteps, repeats);
        System.out.printf(Locale.US,
                "benchmark=%s engine=sequential scenario=%s workers=1 steps=%d warmup=%d repeats=%d balls=%d elapsed_ms=%.3f avg_step_ms=%.6f min_step_ms=%.6f max_step_ms=%.6f stddev_step_ms=%.6f throughput_steps_per_sec=%.3f checksum=%d%n",
                benchmark,
                scenario.name(),
                steps,
                warmupSteps,
                repeats,
                summary.balls(),
                summary.elapsedMillis(),
                summary.avgStepMillis(),
                summary.minStepMillis(),
                summary.maxStepMillis(),
                summary.stddevStepMillis(),
                summary.throughputStepsPerSecond(),
                summary.checksum());
    }

    static void printComparison(String benchmark, Scenario scenario, int workers, int steps, int warmupSteps, int repeats) {
        var task = runTaskScenario(scenario, workers, steps, warmupSteps, repeats);
        var threaded = runThreadedScenario(scenario, workers, steps, warmupSteps, repeats);
        double speedup = threaded.avgStepMillis() / task.avgStepMillis();
        System.out.printf(Locale.US,
                "benchmark=%s scenario=%s workers=%d steps=%d warmup=%d repeats=%d balls=%d task_elapsed_ms=%.3f task_avg_step_ms=%.6f threaded_elapsed_ms=%.3f threaded_avg_step_ms=%.6f speedup_vs_threaded=%.3f checksum=%d%n",
                benchmark,
                scenario.name(),
                workers,
                steps,
                warmupSteps,
                repeats,
                task.balls(),
                task.elapsedMillis(),
                task.avgStepMillis(),
                threaded.elapsedMillis(),
                threaded.avgStepMillis(),
                speedup,
                task.checksum());
    }

    static void printCompleteComparison(String benchmark, Scenario scenario, int steps, int warmupSteps, int repeats) {
        var sequential = runSequentialScenario(scenario, steps, warmupSteps, repeats);
        printCompleteLine(benchmark, scenario, "sequential", 1, steps, warmupSteps, repeats, sequential, sequential);

        var bestEngine = "sequential";
        var bestWorkers = 1;
        var bestAvgStepMillis = sequential.avgStepMillis();
        var bestSummary = sequential;

        for (var workers : workerCounts()) {
            var threaded = runThreadedScenario(scenario, workers, steps, warmupSteps, repeats);
            printCompleteLine(benchmark, scenario, "threaded", workers, steps, warmupSteps, repeats, threaded, sequential);
            if (threaded.avgStepMillis() < bestAvgStepMillis) {
                bestEngine = "threaded";
                bestWorkers = workers;
                bestAvgStepMillis = threaded.avgStepMillis();
                bestSummary = threaded;
            }

            var task = runTaskScenario(scenario, workers, steps, warmupSteps, repeats);
            printCompleteLine(benchmark, scenario, "task", workers, steps, warmupSteps, repeats, task, sequential);
            if (task.avgStepMillis() < bestAvgStepMillis) {
                bestEngine = "task";
                bestWorkers = workers;
                bestAvgStepMillis = task.avgStepMillis();
                bestSummary = task;
            }
        }

        double bestSpeedup = sequential.avgStepMillis() / bestSummary.avgStepMillis();
        System.out.printf(Locale.US,
                "benchmark=%s kind=recommendation scenario=%s best_engine=%s best_workers=%d best_avg_step_ms=%.6f speedup_vs_sequential=%.3f note=%s%n",
                benchmark,
                scenario.name(),
                bestEngine,
                bestWorkers,
                bestSummary.avgStepMillis(),
                bestSpeedup,
                recommendationNote(bestEngine, bestSpeedup));
    }

    static void printTaskProfiling(String benchmark, Scenario scenario, int workers, int steps, int warmupSteps, int repeats) {
        var summary = runTaskScenario(scenario, workers, steps, warmupSteps, repeats);
        var baseline = runTaskScenario(scenario, 1, steps, warmupSteps, repeats);
        double speedup = baseline.avgStepMillis() / summary.avgStepMillis();
        System.out.printf(Locale.US,
                "benchmark=%s scenario=%s workers=%d steps=%d warmup=%d repeats=%d balls=%d elapsed_ms=%.3f avg_step_ms=%.6f speedup_vs_1_worker=%.3f checksum=%d%n",
                benchmark,
                scenario.name(),
                workers,
                steps,
                warmupSteps,
                repeats,
                summary.balls(),
                summary.elapsedMillis(),
                summary.avgStepMillis(),
                speedup,
                summary.checksum());
    }

    private static void printCompleteLine(
            String benchmark,
            Scenario scenario,
            String engine,
            int workers,
            int steps,
            int warmupSteps,
            int repeats,
            BenchmarkSummary summary,
            BenchmarkSummary sequentialBaseline) {
        double speedup = sequentialBaseline.avgStepMillis() / summary.avgStepMillis();
        boolean checksumMatchesSequential = summary.checksum() == sequentialBaseline.checksum();
        System.out.printf(Locale.US,
                "benchmark=%s kind=result scenario=%s engine=%s workers=%d steps=%d warmup=%d repeats=%d balls=%d elapsed_ms=%.3f avg_step_ms=%.6f min_step_ms=%.6f max_step_ms=%.6f stddev_step_ms=%.6f throughput_steps_per_sec=%.3f speedup_vs_sequential=%.3f checksum=%d checksum_matches_sequential=%s diagnosis=%s%n",
                benchmark,
                scenario.name(),
                engine,
                workers,
                steps,
                warmupSteps,
                repeats,
                summary.balls(),
                summary.elapsedMillis(),
                summary.avgStepMillis(),
                summary.minStepMillis(),
                summary.maxStepMillis(),
                summary.stddevStepMillis(),
                summary.throughputStepsPerSecond(),
                speedup,
                summary.checksum(),
                checksumMatchesSequential,
                diagnosis(engine, speedup, summary, checksumMatchesSequential));
    }

    private static BenchmarkSummary summarize(int repeats, RepeatSupplier supplier) {
        if (repeats < 1) {
            throw new IllegalArgumentException("repeats must be >= 1");
        }
        double elapsed = 0.0;
        double avgStep = 0.0;
        double minStep = Double.POSITIVE_INFINITY;
        double maxStep = 0.0;
        var samples = new ArrayList<Double>(repeats);
        long checksum = Long.MIN_VALUE;
        int balls = -1;
        for (int i = 0; i < repeats; i++) {
            var run = supplier.get();
            elapsed += run.elapsedMillis();
            avgStep += run.avgStepMillis();
            minStep = Math.min(minStep, run.avgStepMillis());
            maxStep = Math.max(maxStep, run.avgStepMillis());
            samples.add(run.avgStepMillis());
            balls = run.balls();
            if (checksum == Long.MIN_VALUE) {
                checksum = run.checksum();
            } else if (checksum != run.checksum()) {
                throw new IllegalStateException("benchmark checksum changed across repeats");
            }
        }
        double meanStep = avgStep / repeats;
        double variance = 0.0;
        for (var sample : samples) {
            double delta = sample - meanStep;
            variance += delta * delta;
        }
        double stddevStep = Math.sqrt(variance / repeats);
        return new BenchmarkSummary(
                balls,
                elapsed / repeats,
                meanStep,
                minStep,
                maxStep,
                stddevStep,
                1_000.0 / meanStep,
                checksum);
    }

    private static BenchmarkRun runSequentialOnce(Scenario scenario, int steps, int warmupSteps) {
        var engine = new PhysicsEngine();
        var board = new Board(engine);
        board.init(scenario.confSupplier().get());
        warmup(board, warmupSteps);
        return timedRun(board, steps);
    }

    private static BenchmarkRun runTaskOnce(Scenario scenario, int workers, int steps, int warmupSteps) {
        try (var engine = new TaskBasedPhysicsEngine(workers)) {
            var board = new Board(engine);
            board.init(scenario.confSupplier().get());
            warmup(board, warmupSteps);
            return timedRun(board, steps);
        }
    }

    private static BenchmarkRun runThreadedOnce(Scenario scenario, int workers, int steps, int warmupSteps) {
        try (var engine = new ThreadedPhysicsEngine(workers)) {
            var board = new Board(engine);
            board.init(scenario.confSupplier().get());
            warmup(board, warmupSteps);
            return timedRun(board, steps);
        }
    }

    private static void warmup(Board board, int warmupSteps) {
        for (int i = 0; i < warmupSteps; i++) {
            board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
        }
    }

    private static BenchmarkRun timedRun(Board board, int steps) {
        long start = System.nanoTime();
        for (int i = 0; i < steps; i++) {
            board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
        }
        long elapsed = System.nanoTime() - start;
        double elapsedMillis = elapsed / NANOS_PER_MILLISECOND;
        double avgStepMillis = elapsedMillis / Math.max(1, steps);
        return new BenchmarkRun(board.getBalls().size(), elapsedMillis, avgStepMillis, checksum(board));
    }

    private static long checksum(Board board) {
        long result = board.getBalls().size();
        for (Board.BallSnapshot ball : board.getBalls()) {
            result = 31 * result + Double.doubleToLongBits(ball.pos().x());
            result = 31 * result + Double.doubleToLongBits(ball.pos().y());
            result = 31 * result + Double.doubleToLongBits(ball.radius());
        }
        return result;
    }

    private static String diagnosis(
            String engine,
            double speedup,
            BenchmarkSummary summary,
            boolean checksumMatchesSequential) {
        if (!checksumMatchesSequential) {
            return "different_trajectory_check_rules";
        }
        double relativeNoise = summary.stddevStepMillis() / summary.avgStepMillis();
        if (relativeNoise > 0.25) {
            return "unstable_measurement_repeat_or_profile";
        }
        if (!"sequential".equals(engine) && speedup < 0.95) {
            return "parallel_overhead_dominates";
        }
        if (!"sequential".equals(engine) && speedup > 1.10) {
            return "parallelism_effective";
        }
        return "comparable";
    }

    private static String recommendationNote(String bestEngine, double bestSpeedup) {
        if ("sequential".equals(bestEngine)) {
            return "parallel_versions_do_not_pay_off_for_this_workload";
        }
        if (bestSpeedup < 1.10) {
            return "winner_is_close_validate_with_more_repeats";
        }
        return "best_candidate_for_this_workload";
    }

    @FunctionalInterface
    private interface RepeatSupplier {

        BenchmarkRun get();
    }

    record Scenario(String name, Supplier<BoardConf> confSupplier) {
    }

    record BenchmarkRun(int balls, double elapsedMillis, double avgStepMillis, long checksum) {
    }

    record BenchmarkSummary(
            int balls,
            double elapsedMillis,
            double avgStepMillis,
            double minStepMillis,
            double maxStepMillis,
            double stddevStepMillis,
            double throughputStepsPerSecond,
            long checksum) {
    }
}
