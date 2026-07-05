package pcd.poool.model.physics.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.PhysicsDefaults;

class BoardConfigurationTest {

    private static final int MINIMAL_SMALL_BALLS = 2;
    private static final int LARGE_GRID_ROWS = 20;
    private static final int LARGE_GRID_COLUMNS = 20;
    private static final int MASSIVE_GRID_ROWS = 30;
    private static final int MASSIVE_GRID_COLUMNS = 150;

    /**
     * Verifies that the MinimalBoardConf creates the expected count of small balls (2).
     */
    @Test
    void minimalConfigurationCreatesExpectedSmallBallCount() {
        assertEquals(MINIMAL_SMALL_BALLS, new MinimalBoardConf().getSmallBalls().size());
    }

    /**
     * Verifies that the LargeBoardConf creates a full grid of small balls (20x20).
     */
    @Test
    void largeConfigurationCreatesGridSizedSmallBallCount() {
        assertEquals(LARGE_GRID_ROWS * LARGE_GRID_COLUMNS, new LargeBoardConf().getSmallBalls().size());
    }

    /**
     * Verifies that the MassiveBoardConf creates a massive grid of small balls (30x150).
     */
    @Test
    void massiveConfigurationCreatesGridSizedSmallBallCount() {
        assertEquals(MASSIVE_GRID_ROWS * MASSIVE_GRID_COLUMNS, new MassiveBoardConf().getSmallBalls().size());
    }

    /**
     * Verifies that the default holes are positioned correctly at the upper corners of the board boundary
     * and have the standard default hole radius.
     */
    @Test
    void defaultHolesArePlacedAtUpperBoardCorners() {
        var configuration = new MinimalBoardConf();
        var bounds = configuration.getBoardBoundary();
        var holes = configuration.getHoles();

        assertEquals(new P2d(bounds.x0(), bounds.y1()), holes.get(0).center());
        assertEquals(new P2d(bounds.x1(), bounds.y1()), holes.get(1).center());
        assertEquals(PhysicsDefaults.DEFAULT_HOLE_RADIUS, holes.get(0).radius());
        assertEquals(PhysicsDefaults.DEFAULT_HOLE_RADIUS, holes.get(1).radius());
    }

    /**
     * Verifies that standard game configurations derive ball masses from their radii
     * according to the uniform density formula.
     */
    @Test
    void standardConfigurationDerivesMassesFromUniformMaterial() {
        var configuration = new StandardGameBoardConf();
        var playerBall = configuration.getPlayerBall();
        var firstSmallBall = configuration.getSmallBalls().get(0);

        assertEquals(Ball.massForRadius(playerBall.getRadius()), playerBall.getMass());
        assertEquals(Ball.massForRadius(firstSmallBall.getRadius()), firstSmallBall.getMass());
    }
}
