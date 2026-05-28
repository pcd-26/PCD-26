package pcd.poool.model.physics.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.physics.PhysicsDefaults;

class BoardConfigurationTest {

    private static final int MINIMAL_SMALL_BALLS = 2;
    private static final int LARGE_GRID_ROWS = 20;
    private static final int LARGE_GRID_COLUMNS = 20;
    private static final int MASSIVE_GRID_ROWS = 30;
    private static final int MASSIVE_GRID_COLUMNS = 150;

    @Test
    void minimalConfigurationCreatesExpectedSmallBallCount() {
        assertEquals(MINIMAL_SMALL_BALLS, new MinimalBoardConf().getSmallBalls().size());
    }

    @Test
    void largeConfigurationCreatesGridSizedSmallBallCount() {
        assertEquals(LARGE_GRID_ROWS * LARGE_GRID_COLUMNS, new LargeBoardConf().getSmallBalls().size());
    }

    @Test
    void massiveConfigurationCreatesGridSizedSmallBallCount() {
        assertEquals(MASSIVE_GRID_ROWS * MASSIVE_GRID_COLUMNS, new MassiveBoardConf().getSmallBalls().size());
    }

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
}
