package pcd.poool.model.physics;

import java.util.List;
import pcd.poool.model.common.math.P2d;

/**
 * Configuration contract used to initialize a board scenario.
 */
public interface BoardConf {

	Boundary getBoardBoundary();
	
	Ball getPlayerBall();
	
	List<Ball> getSmallBalls();

	default List<Hole> getHoles() {
		var bounds = getBoardBoundary();
		return List.of(
				new Hole(new P2d(bounds.x0(), bounds.y1()), PhysicsDefaults.DEFAULT_HOLE_RADIUS),
				new Hole(new P2d(bounds.x1(), bounds.y1()), PhysicsDefaults.DEFAULT_HOLE_RADIUS));
	}
}
