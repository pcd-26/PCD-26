package pcd.poool.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.Boundary;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;

/**
 * Profiling benchmark for the threaded physics pipeline.
 *
 * <p>The benchmark compares a sparse and a clustered layout with the same
 * number of balls so bottlenecks in the spatial phases become visible.
 */
public class ThreadedPhysicsProfilingBenchmark {

    private static final int DEFAULT_STEPS = 120;
    private static final int DEFAULT_SMALL_BALLS = 1_600;
    private static final Boundary BOARD_BOUNDARY = new Boundary(-1.5, -1.0, 1.5, 1.0);
    private static final double CUE_RADIUS = 0.05;
    private static final double SMALL_BALL_RADIUS = 0.01;
    private static final V2d RESTING = new V2d(0, 0);
    private static final String OUTPUT_FORMAT =
            "%s steps=%d workers=%d balls=%d avg_total_ms=%.6f avg_integration_ms=%.6f "
            + "avg_grid_build_ms=%.6f avg_grid_merge_ms=%.6f avg_pair_ms=%.6f "
            + "avg_resolution_ms=%.6f avg_pairs=%d avg_cells=%d max_cell_occupancy=%d "
            + "integration_worker_imbalance=%.3f grid_worker_imbalance=%.3f%n";

    private ThreadedPhysicsProfilingBenchmark() {
    }

    /**
     * Runs the profiling benchmark.
     *
     * @param args optional arguments: number of steps, worker count, small-ball count
     */
    public static void main(String[] args) {
        int steps = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_STEPS;
        int workers = args.length > 1
                ? Integer.parseInt(args[1])
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int smallBalls = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_SMALL_BALLS;

        runScenario("sparse", steps, workers, new SparseBoardConf(smallBalls));
        runScenario("clustered", steps, workers, new ClusteredBoardConf(smallBalls));
    }

    private static void runScenario(String name, int steps, int workers, BoardConf conf) {
        try (var engine = new ThreadedPhysicsEngine(workers)) {
            var board = new Board(engine);
            board.init(conf);
            var totals = new ProfileTotals();
            for (int i = 0; i < steps; i++) {
                totals.add(engine.profileStep(board, PhysicsDefaults.FIXED_STEP_MILLIS));
            }
            System.out.printf(Locale.US, OUTPUT_FORMAT,
                    name,
                    steps,
                    engine.workerCount(),
                    board.getBalls().size(),
                    totals.averageTotalMillis(steps),
                    totals.averageIntegrationMillis(steps),
                    totals.averageGridBuildMillis(steps),
                    totals.averageGridMergeMillis(steps),
                    totals.averagePairMillis(steps),
                    totals.averageResolutionMillis(steps),
                    totals.averagePairs(steps),
                    totals.averageCells(steps),
                    totals.maxCellOccupancy,
                    totals.integrationWorkerImbalance(),
                    totals.gridWorkerImbalance());
        }
    }

    private static final class ProfileTotals {

        private double integrationMillis;
        private double gridBuildMillis;
        private double gridMergeMillis;
        private double pairMillis;
        private double resolutionMillis;
        private double holeMillis;
        private long pairs;
        private long cells;
        private int maxCellOccupancy;
        private double[] integrationWorkerMillis;
        private double[] localGridWorkerMillis;
        private int[] integrationWorkerItems;
        private int[] localGridWorkerItems;

        private void add(ThreadedPhysicsEngine.StepProfile profile) {
            ensureWorkerCapacity(profile.integrationWorkerMillis().size());
            integrationMillis += profile.integrationMillis();
            gridBuildMillis += profile.localGridBuildMillis();
            gridMergeMillis += profile.gridMergeMillis();
            pairMillis += profile.pairCollectionMillis();
            resolutionMillis += profile.collisionResolutionMillis();
            holeMillis += profile.holeInteractionMillis();
            pairs += profile.candidatePairs();
            cells += profile.mergedCells();
            maxCellOccupancy = Math.max(maxCellOccupancy, profile.maxCellOccupancy());
            accumulate(profile.integrationWorkerMillis(), integrationWorkerMillis);
            accumulate(profile.localGridWorkerMillis(), localGridWorkerMillis);
            accumulate(profile.integrationWorkerItems(), integrationWorkerItems);
            accumulate(profile.localGridWorkerItems(), localGridWorkerItems);
        }

        private double averageTotalMillis(int steps) {
            return (integrationMillis + holeMillis + gridBuildMillis + gridMergeMillis + pairMillis + resolutionMillis)
                    / steps;
        }

        private double averageIntegrationMillis(int steps) {
            return integrationMillis / steps;
        }

        private double averageGridBuildMillis(int steps) {
            return gridBuildMillis / steps;
        }

        private double averageGridMergeMillis(int steps) {
            return gridMergeMillis / steps;
        }

        private double averagePairMillis(int steps) {
            return pairMillis / steps;
        }

        private double averageResolutionMillis(int steps) {
            return resolutionMillis / steps;
        }

        private int averagePairs(int steps) {
            return (int) (pairs / steps);
        }

        private int averageCells(int steps) {
            return (int) (cells / steps);
        }

        private double integrationWorkerImbalance() {
            return imbalanceRatio(integrationWorkerMillis, integrationWorkerItems);
        }

        private double gridWorkerImbalance() {
            return imbalanceRatio(localGridWorkerMillis, localGridWorkerItems);
        }

        private void accumulate(List<Double> source, double[] target) {
            for (int i = 0; i < source.size(); i++) {
                target[i] += source.get(i);
            }
        }

        private void accumulate(List<Integer> source, int[] target) {
            for (int i = 0; i < source.size(); i++) {
                target[i] += source.get(i);
            }
        }

        private double imbalanceRatio(double[] workerMillis, int[] workerItems) {
            double max = 0.0;
            double min = Double.POSITIVE_INFINITY;
            boolean foundActiveWorker = false;
            for (int i = 0; i < workerMillis.length; i++) {
                if (workerItems[i] <= 0) {
                    continue;
                }
                foundActiveWorker = true;
                max = Math.max(max, workerMillis[i]);
                min = Math.min(min, workerMillis[i]);
            }
            if (!foundActiveWorker || min <= 0.0) {
                return 1.0;
            }
            return max / min;
        }

        private void ensureWorkerCapacity(int workerCount) {
            if (integrationWorkerMillis == null) {
                integrationWorkerMillis = new double[workerCount];
                localGridWorkerMillis = new double[workerCount];
                integrationWorkerItems = new int[workerCount];
                localGridWorkerItems = new int[workerCount];
                return;
            }
            if (workerCount != integrationWorkerMillis.length) {
                throw new IllegalStateException("inconsistent worker metric size across benchmark steps");
            }
        }
    }

    private abstract static class BaseBenchmarkConf implements BoardConf {

        private final int smallBallCount;

        private BaseBenchmarkConf(int smallBallCount) {
            this.smallBallCount = smallBallCount;
        }

        @Override
        public Boundary getBoardBoundary() {
            return BOARD_BOUNDARY;
        }

        @Override
        public Ball getPlayerBall() {
            return Ball.ofUniformMaterial(new P2d(0, -0.85), CUE_RADIUS, RESTING);
        }

        @Override
        public Ball getBotBall() {
            return Ball.ofUniformMaterial(new P2d(0, 0.85), CUE_RADIUS, RESTING);
        }

        protected int smallBallCount() {
            return smallBallCount;
        }
    }

    private static final class SparseBoardConf extends BaseBenchmarkConf {

        private SparseBoardConf(int smallBallCount) {
            super(smallBallCount);
        }

        @Override
        public List<Ball> getSmallBalls() {
            var balls = new ArrayList<Ball>(smallBallCount());
            int columns = (int) Math.ceil(Math.sqrt(smallBallCount()));
            double spacing = SMALL_BALL_RADIUS * 3.5;
            double startX = -1.2;
            double startY = -0.45;
            for (int i = 0; i < smallBallCount(); i++) {
                int row = i / columns;
                int col = i % columns;
                double x = startX + col * spacing;
                double y = startY + row * spacing;
                balls.add(Ball.ofUniformMaterial(new P2d(x, y), SMALL_BALL_RADIUS, RESTING));
            }
            return balls;
        }
    }

    private static final class ClusteredBoardConf extends BaseBenchmarkConf {

        private ClusteredBoardConf(int smallBallCount) {
            super(smallBallCount);
        }

        @Override
        public List<Ball> getSmallBalls() {
            var balls = new ArrayList<Ball>(smallBallCount());
            int columns = (int) Math.ceil(Math.sqrt(smallBallCount()));
            double spacing = SMALL_BALL_RADIUS * 1.05;
            double startX = -0.18;
            double startY = -0.18;
            for (int i = 0; i < smallBallCount(); i++) {
                int row = i / columns;
                int col = i % columns;
                double x = startX + col * spacing;
                double y = startY + row * spacing;
                balls.add(Ball.ofUniformMaterial(new P2d(x, y), SMALL_BALL_RADIUS, RESTING));
            }
            return balls;
        }
    }
}
