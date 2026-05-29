package pcd.poool.model.physics;

import java.util.List;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

/**
 * Configuration contract used to initialize a board scenario.
 */
public interface BoardConf {

	Boundary getBoardBoundary();
	
	Ball getPlayerBall();

	default Ball getBotBall() {
		var bounds = getBoardBoundary();
		return new Ball(new P2d(0, bounds.y1() * 0.75), 0.05, 1.5, new V2d(0, 0));
	}
	
	List<Ball> getSmallBalls();

	default List<Hole> getHoles() {
		var bounds = getBoardBoundary();
		return List.of(
				new Hole(new P2d(bounds.x0(), bounds.y1()), PhysicsDefaults.DEFAULT_HOLE_RADIUS),
				new Hole(new P2d(bounds.x1(), bounds.y1()), PhysicsDefaults.DEFAULT_HOLE_RADIUS));
	}
}
