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

    private static final int DEFAULT_STEPS = 30;
    private static final int DEFAULT_WARMUP_STEPS = 5;
    private static final int DEFAULT_REPEATS = 2;
    private static final String OUTPUT_FORMAT =
            "%s steps=%d warmup=%d repeats=%d workers=%d balls=%d "
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
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_STEPS;
        int warmupSteps = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_WARMUP_STEPS;
        int repeats = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_REPEATS;

        for (var scenario : scenarios()) {
            for (var workers : PhysicsBenchmarkSupport.workerCounts()) {
                printScenario(scenario, workers, steps, warmupSteps, repeats);
            }
        }
    }

    private static List<Scenario> scenarios() {
        return List.of(
                new Scenario("small", StandardGameBoardConf::new),
                new Scenario("medium", ThousandBallsBoardConf::new),
                new Scenario("high-load", MassiveBoardConf::new));
    }

    private static void printScenario(Scenario scenario, int workers, int steps, int warmupSteps, int repeats) {
        var summary = summarizeScenario(scenario, workers, steps, warmupSteps, repeats);
        System.out.printf(Locale.US, OUTPUT_FORMAT,
                scenario.name(),
                steps,
                warmupSteps,
                repeats,
                workers,
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
            int workers,
            int steps,
            int warmupSteps,
            int repeats) {
        if (repeats < 1) {
            throw new IllegalArgumentException("repeats must be >= 1");
        }
        int balls = -1;
        double elapsedWallMillis = 0.0;
        var totals = new PhaseTotals();
        for (int repeat = 0; repeat < repeats; repeat++) {
            try (var engine = new TaskBasedPhysicsEngine(workers)) {
                var board = new Board(engine);
                board.init(scenario.confSupplier().get());
                warmup(board, warmupSteps);

                long start = System.nanoTime();
                for (int step = 0; step < steps; step++) {
                    totals.add(engine.profileStep(board, PhysicsDefaults.FIXED_STEP_MILLIS));
                }
                elapsedWallMillis += (System.nanoTime() - start) / 1_000_000.0;
                balls = board.getBalls().size();
            }
        }
        return totals.toSummary(balls, elapsedWallMillis / repeats, steps * repeats);
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
