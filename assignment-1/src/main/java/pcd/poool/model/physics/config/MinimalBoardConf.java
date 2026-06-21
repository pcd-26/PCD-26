package pcd.poool.model.physics.config;

import java.util.ArrayList;
import java.util.List;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.Boundary;

/**
 * Minimal deterministic board configuration used by physics tests and quick
 * experiments.
 */
public class MinimalBoardConf implements BoardConf {

	private static final Boundary BOARD_BOUNDARY = new Boundary(-1.5, -1.0, 1.5, 1.0);
	private static final P2d PLAYER_START = new P2d(0, 0);
	private static final V2d PLAYER_INITIAL_VELOCITY = new V2d(0, 0.5);
	private static final double PLAYER_RADIUS = 0.06;

	private static final double FIRST_SMALL_BALL_RADIUS = 0.05;
	private static final P2d FIRST_SMALL_BALL_START = new P2d(0, 0.5);

	private static final double SECOND_SMALL_BALL_RADIUS = 0.025;
	private static final P2d SECOND_SMALL_BALL_START = new P2d(0.05, 0.55);
	private static final V2d RESTING = new V2d(0, 0);

	/**
	 * Creates the minimal board configuration.
	 */
	public MinimalBoardConf() {
	}

	@Override
	public Ball getPlayerBall() {
		return Ball.ofUniformMaterial(PLAYER_START, PLAYER_RADIUS, PLAYER_INITIAL_VELOCITY);
	}

	@Override
	public List<Ball> getSmallBalls() {		
        var balls = new ArrayList<Ball>();
		var b1 = Ball.ofUniformMaterial(FIRST_SMALL_BALL_START, FIRST_SMALL_BALL_RADIUS, RESTING);
		var b2 = Ball.ofUniformMaterial(SECOND_SMALL_BALL_START, SECOND_SMALL_BALL_RADIUS, RESTING);
		balls.add(b1);
		balls.add(b2);
		return balls;
	}

	@Override
	public Boundary getBoardBoundary() {
        return BOARD_BOUNDARY;
	}

}
