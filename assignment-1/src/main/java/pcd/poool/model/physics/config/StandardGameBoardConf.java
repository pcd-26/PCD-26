package pcd.poool.model.physics.config;

import java.util.ArrayList;
import java.util.List;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.Ball;
import pcd.poool.model.physics.BoardConf;
import pcd.poool.model.physics.Boundary;

/**
 * Small playable board configuration for the sequential game baseline.
 *
 * <p>The layout keeps the number of balls low enough for quick manual games
 * while still exercising collisions, bot shots, scoring, and end-game rules.
 */
public class StandardGameBoardConf implements BoardConf {

    private static final Boundary BOARD_BOUNDARY = new Boundary(-1.5, -1.0, 1.5, 1.0);
    private static final double CUE_RADIUS = 0.05;
    private static final double CUE_MASS = 1.5;
    private static final double SMALL_BALL_RADIUS = 0.035;
    private static final double SMALL_BALL_MASS = 0.4;
    private static final V2d RESTING = new V2d(0, 0);

    /**
     * Creates the standard playable board configuration.
     */
    public StandardGameBoardConf() {
    }

    @Override
    public Boundary getBoardBoundary() {
        return BOARD_BOUNDARY;
    }

    @Override
    public Ball getPlayerBall() {
        return new Ball(new P2d(0, -0.72), CUE_RADIUS, CUE_MASS, RESTING);
    }

    @Override
    public Ball getBotBall() {
        return new Ball(new P2d(0, 0.62), CUE_RADIUS, CUE_MASS, RESTING);
    }

    @Override
    public List<Ball> getSmallBalls() {
        var balls = new ArrayList<Ball>();
        double startX = -0.16;
        double startY = -0.10;
        double spacing = SMALL_BALL_RADIUS * 2.4;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col <= row; col++) {
                double x = startX + row * spacing;
                double y = startY + (col - row / 2.0) * spacing;
                balls.add(new Ball(new P2d(x, y), SMALL_BALL_RADIUS, SMALL_BALL_MASS, RESTING));
            }
        }
        return balls;
    }
}
