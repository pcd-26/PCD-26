package pcd.poool.model.physics.config;

import java.util.ArrayList;
import java.util.List;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.Ball;
import pcd.poool.model.physics.BoardConf;
import pcd.poool.model.physics.Boundary;

/**
 * Minimal deterministic board configuration used by physics tests and quick
 * experiments.
 */
public class MinimalBoardConf implements BoardConf {

	private static final Boundary BOARD_BOUNDARY = new Boundary(-1.5, -1.0, 1.5, 1.0);
	private static final P2d PLAYER_START = new P2d(0, 0);
	private static final V2d PLAYER_INITIAL_VELOCITY = new V2d(0, 0.5);
	private static final double PLAYER_RADIUS = 0.06;
	private static final double PLAYER_MASS = 1.0;

	private static final double FIRST_SMALL_BALL_RADIUS = 0.05;
	private static final double FIRST_SMALL_BALL_MASS = 0.75;
	private static final P2d FIRST_SMALL_BALL_START = new P2d(0, 0.5);

	private static final double SECOND_SMALL_BALL_RADIUS = 0.025;
	private static final double SECOND_SMALL_BALL_MASS = 0.25;
	private static final P2d SECOND_SMALL_BALL_START = new P2d(0.05, 0.55);
	private static final V2d RESTING = new V2d(0, 0);

	/**
	 * Creates the minimal board configuration.
	 */
	public MinimalBoardConf() {
	}

	@Override
	public Ball getPlayerBall() {
    	return new Ball(PLAYER_START, PLAYER_RADIUS, PLAYER_MASS, PLAYER_INITIAL_VELOCITY);
	}

	@Override
	public List<Ball> getSmallBalls() {		
        var balls = new ArrayList<Ball>();
    	var b1 = new Ball(FIRST_SMALL_BALL_START, FIRST_SMALL_BALL_RADIUS, FIRST_SMALL_BALL_MASS, RESTING);
    	var b2 = new Ball(SECOND_SMALL_BALL_START, SECOND_SMALL_BALL_RADIUS, SECOND_SMALL_BALL_MASS, RESTING);
    	balls.add(b1);
    	balls.add(b2);
    	return balls;
	}

	@Override
	public Boundary getBoardBoundary() {
        return BOARD_BOUNDARY;
	}

}
