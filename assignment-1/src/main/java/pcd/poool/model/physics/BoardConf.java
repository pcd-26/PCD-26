package pcd.poool.model.physics;

import java.util.List;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

/**
 * Configuration contract used to initialize a board scenario.
 */
public interface BoardConf {

	/**
	 * Gets the playable boundary.
	 *
	 * @return rectangular area where balls can move
	 */
	Boundary getBoardBoundary();
	
	/**
	 * Creates the initial human cue ball.
	 *
	 * @return initial human cue ball
	 */
	Ball getPlayerBall();

	/**
	 * Creates the initial bot cue ball.
	 *
	 * @return initial bot cue ball
	 */
	default Ball getBotBall() {
		var bounds = getBoardBoundary();
		return Ball.ofUniformMaterial(new P2d(0, bounds.y1() * 0.75), 0.05, new V2d(0, 0));
	}
	
	/**
	 * Creates the initial small balls.
	 *
	 * @return initial small balls
	 */
	List<Ball> getSmallBalls();

	/**
	 * Creates the holes used by the game.
	 *
	 * @return holes used by the game; defaults to the upper board corners
	 */
	default List<Hole> getHoles() {
		var bounds = getBoardBoundary();
		return List.of(
				new Hole(new P2d(bounds.x0(), bounds.y1()), PhysicsDefaults.DEFAULT_HOLE_RADIUS),
				new Hole(new P2d(bounds.x1(), bounds.y1()), PhysicsDefaults.DEFAULT_HOLE_RADIUS));
	}
}
