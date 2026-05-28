package pcd.poool.model.physics;

import java.util.List;

/**
 * Configuration contract used to initialize a board scenario.
 */
public interface BoardConf {

	Boundary getBoardBoundary();
	
	Ball getPlayerBall();
	
	List<Ball> getSmallBalls();
}
