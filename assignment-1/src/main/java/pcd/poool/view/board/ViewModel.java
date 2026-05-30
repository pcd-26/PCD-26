package pcd.poool.view.board;

import java.util.ArrayList;
import java.util.List;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.game.GameSnapshot;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.Board;

/**
 * Thread-safe snapshot consumed by the Swing renderer.
 *
 * <p>The simulation loop writes this model before requesting a repaint, while
 * Swing's EDT reads it during painting. Methods are synchronized to keep those
 * two activities from observing partially updated render data.
 */
public class ViewModel {

	/** Immutable render data for a ball. */
	public static record BallViewInfo(P2d pos, double radius) {}
	/** Immutable render data for a hole. */
	public static record HoleViewInfo(P2d center, double radius) {}
	/**
	 * Shot preview data. {@code player} controls the preview color and also
	 * records who owns the current aiming interaction.
	 */
	public static record ShotPreviewInfo(P2d from, P2d to, double intensity, Player player) {}

	private ArrayList<BallViewInfo> balls;
	private ArrayList<HoleViewInfo> holes;
	private BallViewInfo player;
	private BallViewInfo bot;
	private ShotPreviewInfo shotPreview;
	private int framePerSec;
	private GameSnapshot game;
	
	public ViewModel() {
		balls = new ArrayList<BallViewInfo>();
		holes = new ArrayList<HoleViewInfo>();
		framePerSec = 0;
	}
	
	/**
	 * Copies board state into rendering data.
	 *
	 * @param board current physical board
	 * @param framePerSec measured frame rate
	 */
	public synchronized void update(Board board, int framePerSec) {
		balls.clear();
		for (var b: board.getBalls()) {
			balls.add(new BallViewInfo(b.pos(), b.radius()));
		}
		holes.clear();
		for (var h: board.getHoles()) {
			holes.add(new HoleViewInfo(h.center(), h.radius()));
		}
		this.framePerSec = framePerSec;
		var p = board.getPlayerBall();
		player = p == null ? null : new BallViewInfo(p.pos(), p.radius());
		var b = board.getBotBall();
		bot = b == null ? null : new BallViewInfo(b.pos(), b.radius());
	}

	/**
	 * Copies board and game state into rendering data.
	 *
	 * @param board current physical board
	 * @param game current game snapshot
	 * @param framePerSec measured frame rate
	 */
	public synchronized void update(Board board, GameSnapshot game, int framePerSec) {
		update(board, framePerSec);
		this.game = game;
	}
	
	public synchronized List<BallViewInfo> getBalls(){
		var copy = new ArrayList<BallViewInfo>();
		copy.addAll(balls);
		return copy;
		
	}

	public synchronized int getFramePerSec() {
		return framePerSec;
	}

	public synchronized BallViewInfo getPlayerBall() {
		return player;
	}

	public synchronized BallViewInfo getBotBall() {
		return bot;
	}

	public synchronized GameSnapshot getGame() {
		return game;
	}

	/**
	 * Sets a human-owned shot preview.
	 */
	public synchronized void setShotPreview(P2d from, P2d to, double intensity) {
		setShotPreview(from, to, intensity, Player.HUMAN);
	}

	/**
	 * Sets the current shot preview.
	 *
	 * @param from cue-ball position
	 * @param to selected target point used for the solid preview segment
	 * @param intensity shot strength used by the HUD label
	 * @param player owner of the preview
	 */
	public synchronized void setShotPreview(P2d from, P2d to, double intensity, Player player) {
		shotPreview = new ShotPreviewInfo(from, to, intensity, player);
	}

	/**
	 * Removes any active shot preview.
	 */
	public synchronized void clearShotPreview() {
		shotPreview = null;
	}

	public synchronized ShotPreviewInfo getShotPreview() {
		return shotPreview;
	}

	public synchronized List<HoleViewInfo> getHoles() {
		return new ArrayList<HoleViewInfo>(holes);
	}
	
}
