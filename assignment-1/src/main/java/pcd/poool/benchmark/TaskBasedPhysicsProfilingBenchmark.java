package pcd.poool.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.config.MassiveBoardConf;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.model.physics.config.ThousandBallsBoardConf;
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;

/**
 * Profiling benchmark for the task-based physics pipeline.
 *
 * <p>This benchmark uses the engine's internal per-phase profiling snapshot so
 * we can see where the task-based runtime spends time: integration, hole
 * handling, grid construction, merge, pair collection, collision resolution,
 * and final apply.
 */
public class TaskBasedPhysicsProfilingBenchmark {
    private static final String OUTPUT_FORMAT =
            "%s config=%s steps=%d warmup=%d repeats=%d workers=%d balls=%d "
            + "avg_total_ms=%.6f avg_integration_ms=%.6f avg_hole_ms=%.6f "
            + "avg_grid_build_ms=%.6f avg_grid_merge_ms=%.6f avg_pair_ms=%.6f "
            + "avg_resolution_ms=%.6f avg_apply_ms=%.6f avg_pairs=%d avg_cells=%d "
            + "max_cell_occupancy=%d elapsed_wall_ms=%.3f%n";

    private TaskBasedPhysicsProfilingBenchmark() {
    }

    /**
     * Runs the profiling benchmark.
     *
     * @param args optional arguments: number of measured steps, warmup steps,
     *             and repeat count
     */
    public static void main(String[] args) {
        var config = BenchmarkConfig.taskProfilingDefaults();
        if (args.length > 0) {
            config = config.withSteps(Integer.parseInt(args[0]));
        }
        if (args.length > 1) {
            config = config.withWarmupRuns(Integer.parseInt(args[1]));
        }
        if (args.length > 2) {
            config = config.withMeasuredRuns(Integer.parseInt(args[2]));
        }

        for (var scenario : scenarios()) {
            for (var workers : PhysicsBenchmarkSupport.workerCounts()) {
                printScenario(scenario, config.withThreads(workers));
            }
        }
    }

    private static List<Scenario> scenarios() {
        return List.of(
                new Scenario("small", StandardGameBoardConf::new),
                new Scenario("medium", ThousandBallsBoardConf::new),
                new Scenario("high-load", MassiveBoardConf::new));
    }

    private static void printScenario(Scenario scenario, BenchmarkConfig config) {
        var summary = summarizeScenario(scenario, config);
        System.out.printf(Locale.US, OUTPUT_FORMAT,
                scenario.name(),
                config.toKeyValueString(),
                config.steps(),
                config.warmupRuns(),
                config.measuredRuns(),
                config.effectiveThreads(),
                summary.balls(),
                summary.avgTotalMillis(),
                summary.avgIntegrationMillis(),
                summary.avgHoleMillis(),
                summary.avgGridBuildMillis(),
                summary.avgGridMergeMillis(),
                summary.avgPairMillis(),
                summary.avgResolutionMillis(),
                summary.avgApplyMillis(),
                summary.avgPairs(),
                summary.avgCells(),
                summary.maxCellOccupancy(),
                summary.elapsedWallMillis());
    }

    private static ProfileSummary summarizeScenario(
            Scenario scenario,
            BenchmarkConfig config) {
        int balls = -1;
        double elapsedWallMillis = 0.0;
        var totals = new PhaseTotals();
        for (int repeat = 0; repeat < config.measuredRuns(); repeat++) {
            try (var engine = new TaskBasedPhysicsEngine(config.effectiveThreads())) {
                var board = new Board(engine);
                board.init(scenario.confSupplier().get());
                warmup(board, config.warmupRuns());

                long start = System.nanoTime();
                for (int step = 0; step < config.steps(); step++) {
                    totals.add(engine.profileStep(board, PhysicsDefaults.FIXED_STEP_MILLIS));
                }
                elapsedWallMillis += (System.nanoTime() - start) / 1_000_000.0;
                balls = board.getBalls().size();
            }
        }
        return totals.toSummary(balls, elapsedWallMillis / config.measuredRuns(), config.steps() * config.measuredRuns());
    }

    private static void warmup(Board board, int warmupSteps) {
        for (int i = 0; i < warmupSteps; i++) {
            board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
        }
    }

    private record Scenario(String name, java.util.function.Supplier<pcd.poool.model.physics.common.BoardConf> confSupplier) {
    }

    private static final class PhaseTotals {

        private double integrationMillis;
        private double holeMillis;
        private double gridBuildMillis;
        private double gridMergeMillis;
        private double pairMillis;
        private double resolutionMillis;
        private double applyMillis;
        private long pairs;
        private long cells;
        private int maxCellOccupancy;

        private void add(TaskBasedPhysicsEngine.StepProfile profile) {
            integrationMillis += profile.integrationMillis();
            holeMillis += profile.holeInteractionMillis();
            gridBuildMillis += profile.localGridBuildMillis();
            gridMergeMillis += profile.gridMergeMillis();
            pairMillis += profile.pairCollectionMillis();
            resolutionMillis += profile.collisionResolutionMillis();
            applyMillis += average(profile.applyWorkerMillis());
            pairs += profile.candidatePairs();
            cells += profile.mergedCells();
            maxCellOccupancy = Math.max(maxCellOccupancy, profile.maxCellOccupancy());
        }

        private ProfileSummary toSummary(int balls, double elapsedWallMillis, int samples) {
            double totalMillis = integrationMillis + holeMillis + gridBuildMillis + gridMergeMillis
                    + pairMillis + resolutionMillis + applyMillis;
            return new ProfileSummary(
                    balls,
                    totalMillis / samples,
                    integrationMillis / samples,
                    holeMillis / samples,
                    gridBuildMillis / samples,
                    gridMergeMillis / samples,
                    pairMillis / samples,
                    resolutionMillis / samples,
                    applyMillis / samples,
                    (int) (pairs / samples),
                    (int) (cells / samples),
                    maxCellOccupancy,
                    elapsedWallMillis);
        }

        private double average(List<Double> values) {
            if (values.isEmpty()) {
                return 0.0;
            }
            double sum = 0.0;
            for (var value : values) {
                sum += value;
            }
            return sum / values.size();
        }
    }

    private record ProfileSummary(
            int balls,
            double avgTotalMillis,
            double avgIntegrationMillis,
            double avgHoleMillis,
            double avgGridBuildMillis,
            double avgGridMergeMillis,
            double avgPairMillis,
            double avgResolutionMillis,
            double avgApplyMillis,
            int avgPairs,
            int avgCells,
            int maxCellOccupancy,
            double elapsedWallMillis) {
    }
}
