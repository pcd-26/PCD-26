package pcd.poool.model.physics.config;

import java.util.ArrayList;
import java.util.List;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.Ball;
import pcd.poool.model.physics.BoardConf;
import pcd.poool.model.physics.Boundary;

/**
 * Large grid-based board configuration for performance experiments.
 */
public class LargeBoardConf implements BoardConf {

	private static final Boundary BOARD_BOUNDARY = new Boundary(-1.5, -1.0, 1.5, 1.0);
	private static final P2d PLAYER_START = new P2d(0, -0.75);
	private static final V2d PLAYER_INITIAL_VELOCITY = new V2d(0, 1);
	private static final double PLAYER_RADIUS = 0.05;

	private static final int GRID_ROWS = 20;
	private static final int GRID_COLUMNS = 20;
	private static final double GRID_START_X = -0.25;
	private static final double GRID_START_Y = 0.0;
	private static final double GRID_SPACING = 0.025;
	private static final double SMALL_BALL_RADIUS = 0.01;
	private static final V2d RESTING = new V2d(0, 0);

	/**
	 * Creates the large board configuration.
	 */
	public LargeBoardConf() {
	}

	@Override
	public Ball getPlayerBall() {
		return  Ball.ofUniformMaterial(PLAYER_START, PLAYER_RADIUS, PLAYER_INITIAL_VELOCITY);
	}

	@Override
	public List<Ball> getSmallBalls() {		
        var balls = new ArrayList<Ball>();

    	for (int row = 0; row < GRID_ROWS; row++) {
    		for (int col = 0; col < GRID_COLUMNS; col++) {
        		var px = GRID_START_X + col * GRID_SPACING;
        		var py = GRID_START_Y + row * GRID_SPACING;
				var b = Ball.ofUniformMaterial(new P2d(px, py), SMALL_BALL_RADIUS, RESTING);
            	balls.add(b);    			
    		}
    	}		
    	return balls;
	}

	@Override
	public Boundary getBoardBoundary() {
        return BOARD_BOUNDARY;
	}
}
