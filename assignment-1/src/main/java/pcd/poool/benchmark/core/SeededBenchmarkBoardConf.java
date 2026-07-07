package pcd.poool.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.Boundary;
import pcd.poool.model.physics.config.StandardGameBoardConf;

/**
 * Deterministic benchmark board configuration with a configurable small-ball
 * count and seed.
 */
final class SeededBenchmarkBoardConf implements BoardConf {

    private static final Boundary BOARD_BOUNDARY = new StandardGameBoardConf().getBoardBoundary();
    private static final double INNER_LEFT = -1.20;
    private static final double INNER_RIGHT = 1.20;
    private static final double INNER_BOTTOM = -0.55;
    private static final double INNER_TOP = 0.55;
    private static final double CUE_RADIUS = 0.05;
    private static final double MAX_SMALL_BALL_RADIUS = 0.05;
    private static final double MIN_SMALL_BALL_RADIUS = 0.004;
    private static final double JITTER_FRACTION = 0.18;
    private static final double VELOCITY_SCALE = 0.18;

    private final int ballCount;
    private final long seed;
    private final boolean worstCase;
    private final List<Ball> smallBalls;

    SeededBenchmarkBoardConf(int ballCount, long seed) {
        this(ballCount, seed, false);
    }

    SeededBenchmarkBoardConf(int ballCount, long seed, boolean worstCase) {
        this.ballCount = ballCount;
        this.seed = seed;
        this.worstCase = worstCase;
        this.smallBalls = buildSmallBalls();
    }

    @Override
    public Boundary getBoardBoundary() {
        return BOARD_BOUNDARY;
    }

    @Override
    public Ball getPlayerBall() {
        return Ball.ofUniformMaterial(new P2d(0.0, -0.72), CUE_RADIUS, new V2d(0, 0));
    }

    @Override
    public Ball getBotBall() {
        return Ball.ofUniformMaterial(new P2d(0.0, 0.62), CUE_RADIUS, new V2d(0, 0));
    }

    @Override
    public List<Ball> getSmallBalls() {
        return List.copyOf(smallBalls);
    }

    private List<Ball> buildSmallBalls() {
        if (ballCount == 0) {
            return List.of();
        }

        double usableWidth = INNER_RIGHT - INNER_LEFT;
        double usableHeight = INNER_TOP - INNER_BOTTOM;
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(ballCount * (usableWidth / usableHeight))));
        int rows = Math.max(1, (int) Math.ceil((double) ballCount / columns));
        double cellWidth = usableWidth / columns;
        double cellHeight = usableHeight / rows;
        double radius = Math.max(
                MIN_SMALL_BALL_RADIUS,
                Math.min(MAX_SMALL_BALL_RADIUS, Math.min(cellWidth, cellHeight) * 0.22));
        var rng = new SplittableRandom(seed);
        var balls = new ArrayList<Ball>(ballCount);

        if (worstCase) {
            // Worst case: place all balls in a single cell (size 2 * radius) at the origin
            double boxSize = radius * 0.9;
            int side = (int) Math.ceil(Math.sqrt(ballCount));
            double spacing = side > 1 ? boxSize / (side - 1) : boxSize;
            double startX = -boxSize / 2.0;
            double startY = -boxSize / 2.0;
            double velocityLimit = Math.min(VELOCITY_SCALE, Math.min(cellWidth, cellHeight)) * 0.1; // lower velocity to avoid instant explosion

            for (int index = 0; index < ballCount; index++) {
                int r = index / side;
                int c = index % side;
                double x = startX + c * spacing;
                double y = startY + r * spacing;
                // Add tiny jitter to prevent coincident centers
                x += centeredJitter(rng, spacing * 0.05);
                y += centeredJitter(rng, spacing * 0.05);
                double vx = centeredJitter(rng, velocityLimit);
                double vy = centeredJitter(rng, velocityLimit);
                balls.add(Ball.ofUniformMaterial(new P2d(x, y), radius, new V2d(vx, vy)));
            }
        } else {
            // Average case: uniform grid
            double jitterX = cellWidth * JITTER_FRACTION;
            double jitterY = cellHeight * JITTER_FRACTION;
            double velocityLimit = Math.min(VELOCITY_SCALE, Math.min(cellWidth, cellHeight));

            for (int index = 0; index < ballCount; index++) {
                int row = index / columns;
                int column = index % columns;
                double baseX = INNER_LEFT + (column + 0.5) * cellWidth;
                double baseY = INNER_BOTTOM + (row + 0.5) * cellHeight;
                double x = clamp(baseX + centeredJitter(rng, jitterX), INNER_LEFT + radius, INNER_RIGHT - radius);
                double y = clamp(baseY + centeredJitter(rng, jitterY), INNER_BOTTOM + radius, INNER_TOP - radius);
                double vx = centeredJitter(rng, velocityLimit);
                double vy = centeredJitter(rng, velocityLimit);
                balls.add(Ball.ofUniformMaterial(new P2d(x, y), radius, new V2d(vx, vy)));
            }
        }
        return balls;
    }

    private double centeredJitter(SplittableRandom rng, double amplitude) {
        return (rng.nextDouble() * 2.0 - 1.0) * amplitude;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
