package pcd.poool.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.Boundary;
import pcd.poool.model.physics.config.StandardGameBoardConf;

/**
 * Immutable benchmark workload definition for deterministic physics
 * comparisons.
 *
 * <p>The workload keeps the layout metadata in one place and can build a fresh
 * {@link BoardConf} for each engine so repeated runs always start from the same
 * initial state.
 *
 * @param size workload size category
 * @param collisionProfile collision pattern to stress
 * @param boardBoundary board bounds used by the workload
 * @param seed deterministic seed used to generate the initial state
 */
public record BenchmarkWorkload(
        WorkloadSize size,
        CollisionProfile collisionProfile,
        Boundary boardBoundary,
        long seed) {

    private static final double CUE_RADIUS = 0.05;
    private static final P2d PLAYER_START = new P2d(0.0, -0.72);
    private static final P2d BOT_START = new P2d(0.0, 0.62);
    private static final V2d RESTING = new V2d(0.0, 0.0);
    private static final double MIN_SMALL_BALL_RADIUS = 0.004;
    private static final double MAX_SMALL_BALL_RADIUS = 0.05;
    private static final double LOW_COLLISION_SPREAD = 0.84;
    private static final double HIGH_COLLISION_SPREAD = 0.34;
    private static final double LOW_COLLISION_JITTER = 0.08;
    private static final double HIGH_COLLISION_JITTER = 0.05;
    private static final double LOW_COLLISION_VELOCITY = 0.0;
    private static final double HIGH_COLLISION_VELOCITY = 0.085;

    /**
     * Creates a workload definition.
     *
     * @param size workload size category
     * @param collisionProfile collision pattern to stress
     * @param boardBoundary board bounds used by the workload
     * @param seed deterministic seed used to generate the initial state
     */
    public BenchmarkWorkload {
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(collisionProfile, "collisionProfile");
        Objects.requireNonNull(boardBoundary, "boardBoundary");
    }

    /**
     * Returns the number of small balls in the workload.
     *
     * @return small-ball count
     */
    public int balls() {
        return size.balls();
    }

    /**
     * Returns the number of simulation ticks in the workload.
     *
     * @return tick count
     */
    public int ticks() {
        return size.ticks();
    }

    /**
     * Builds a fresh deterministic board configuration for this workload.
     *
     * @return new board configuration
     */
    public BoardConf createBoardConf() {
        return new DeterministicBenchmarkBoardConf(this);
    }

    /**
     * Supported benchmark size categories.
     */
    public enum WorkloadSize {
        SMALL(100, 600),
        MEDIUM(500, 900),
        LARGE(2_500, 1_200);

        private final int balls;
        private final int ticks;

        WorkloadSize(int balls, int ticks) {
            this.balls = balls;
            this.ticks = ticks;
        }

        /**
         * Gets the small-ball count associated with this size.
         *
         * @return number of small balls
         */
        public int balls() {
            return balls;
        }

        /**
         * Gets the tick count associated with this size.
         *
         * @return number of simulation ticks
         */
        public int ticks() {
            return ticks;
        }
    }

    /**
     * Collision profiles used by the deterministic workloads.
     */
    public enum CollisionProfile {
        LOW_COLLISION,
        HIGH_COLLISION
    }

    private static final class DeterministicBenchmarkBoardConf implements BoardConf {

        private final Boundary boardBoundary;
        private final List<Ball> smallBalls;

        private DeterministicBenchmarkBoardConf(BenchmarkWorkload workload) {
            this.boardBoundary = workload.boardBoundary();
            this.smallBalls = buildSmallBalls(workload);
        }

        @Override
        public Boundary getBoardBoundary() {
            return boardBoundary;
        }

        @Override
        public Ball getPlayerBall() {
            return Ball.ofUniformMaterial(PLAYER_START, CUE_RADIUS, RESTING);
        }

        @Override
        public Ball getBotBall() {
            return Ball.ofUniformMaterial(BOT_START, CUE_RADIUS, RESTING);
        }

        @Override
        public List<Ball> getSmallBalls() {
            return List.copyOf(smallBalls);
        }

        private static List<Ball> buildSmallBalls(BenchmarkWorkload workload) {
            int ballCount = workload.balls();
            if (ballCount <= 0) {
                return List.of();
            }

            double usableWidth = workload.boardBoundary().x1() - workload.boardBoundary().x0();
            double usableHeight = workload.boardBoundary().y1() - workload.boardBoundary().y0();
            double spread = workload.collisionProfile() == CollisionProfile.LOW_COLLISION
                    ? LOW_COLLISION_SPREAD
                    : HIGH_COLLISION_SPREAD;
            double jitterFraction = workload.collisionProfile() == CollisionProfile.LOW_COLLISION
                    ? LOW_COLLISION_JITTER
                    : HIGH_COLLISION_JITTER;
            double velocityScale = workload.collisionProfile() == CollisionProfile.LOW_COLLISION
                    ? LOW_COLLISION_VELOCITY
                    : HIGH_COLLISION_VELOCITY;
            double centerX = (workload.boardBoundary().x0() + workload.boardBoundary().x1()) * 0.5;
            double centerY = (workload.boardBoundary().y0() + workload.boardBoundary().y1()) * 0.5;
            double layoutWidth = usableWidth * spread;
            double layoutHeight = usableHeight * spread;
            double layoutLeft = centerX - layoutWidth * 0.5;
            double layoutTop = centerY + layoutHeight * 0.5;

            int columns = Math.max(1, (int) Math.ceil(Math.sqrt(ballCount * (layoutWidth / layoutHeight))));
            int rows = Math.max(1, (int) Math.ceil((double) ballCount / columns));
            double cellWidth = layoutWidth / columns;
            double cellHeight = layoutHeight / rows;
            double radius = Math.max(
                    MIN_SMALL_BALL_RADIUS,
                    Math.min(MAX_SMALL_BALL_RADIUS, Math.min(cellWidth, cellHeight) * 0.22));
            double jitterX = cellWidth * jitterFraction;
            double jitterY = cellHeight * jitterFraction;
            double velocityLimit = Math.min(velocityScale, Math.min(cellWidth, cellHeight) * 0.45);
            var rng = new SplittableRandom(workload.seed());
            var balls = new ArrayList<Ball>(ballCount);

            for (int index = 0; index < ballCount; index++) {
                int row = index / columns;
                int column = index % columns;
                double baseX = layoutLeft + (column + 0.5) * cellWidth;
                double baseY = layoutTop - (row + 0.5) * cellHeight;
                double x = clamp(baseX + centeredJitter(rng, jitterX), workload.boardBoundary().x0() + radius, workload.boardBoundary().x1() - radius);
                double y = clamp(baseY + centeredJitter(rng, jitterY), workload.boardBoundary().y0() + radius, workload.boardBoundary().y1() - radius);
                double vx = centeredJitter(rng, velocityLimit);
                double vy = centeredJitter(rng, velocityLimit);
                if (workload.collisionProfile() == CollisionProfile.LOW_COLLISION) {
                    vx = 0.0;
                    vy = 0.0;
                } else {
                    vx += signedJitter(index, workload.seed(), velocityLimit * 0.35);
                    vy -= signedJitter(index + 1, workload.seed(), velocityLimit * 0.35);
                }
                balls.add(Ball.ofUniformMaterial(new P2d(x, y), radius, new V2d(vx, vy)));
            }
            return balls;
        }

        private static double centeredJitter(SplittableRandom rng, double amplitude) {
            if (amplitude <= 0.0) {
                return 0.0;
            }
            return (rng.nextDouble() * 2.0 - 1.0) * amplitude;
        }

        private static double signedJitter(int index, long seed, double amplitude) {
            if (amplitude <= 0.0) {
                return 0.0;
            }
            long mix = seed ^ (0x9E3779B97F4A7C15L * (index + 1L));
            return ((mix & 1L) == 0L ? 1.0 : -1.0) * amplitude;
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
