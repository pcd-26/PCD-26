package pcd.poool.view.board;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.game.GameSnapshot;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.Board;
import pcd.poool.model.physics.Hole;

/**
 * Thread-safe snapshot consumed by the Swing renderer.
 *
 * <p>The simulation loop writes this model before requesting a repaint, while
 * Swing's EDT reads it during painting. Methods are synchronized to keep those
 * two activities from observing partially updated render data.
 */
public class ViewModel {

	/**
	 * Immutable render data for a ball.
	 *
	 * @param pos center position
	 * @param radius ball radius
	 */
	public static record BallViewInfo(P2d pos, double radius) {}
	/**
	 * Immutable render data for a hole.
	 *
	 * @param center hole center
	 * @param radius hole radius
	 */
	public static record HoleViewInfo(P2d center, double radius) {}
	/**
	 * Shot preview data. {@code player} controls the preview color and also
	 * records who owns the current aiming interaction.
	 *
	 * @param from preview start position
	 * @param to preview end position
	 * @param intensity shot intensity displayed by the HUD
	 * @param player preview owner
	 */
	public static record ShotPreviewInfo(P2d from, P2d to, double intensity, Player player) {}

	private ArrayList<BallViewInfo> balls;
	private ArrayList<HoleViewInfo> holes;
	private BallViewInfo player;
	private BallViewInfo bot;
	private EnumMap<Player, ShotPreviewInfo> shotPreviews;
	private int framePerSec;
	private GameSnapshot game;
	
	/**
	 * Creates an empty view model.
	 */
	public ViewModel() {
		balls = new ArrayList<BallViewInfo>();
		holes = new ArrayList<HoleViewInfo>();
		shotPreviews = new EnumMap<>(Player.class);
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

	/**
	 * Copies immutable board snapshots into rendering data.
	 *
	 * @param smallBalls current small-ball snapshots
	 * @param playerBall current human cue-ball snapshot, or {@code null}
	 * @param botBall current bot cue-ball snapshot, or {@code null}
	 * @param holes current hole layout
	 * @param game current game snapshot
	 * @param framePerSec measured frame rate
	 */
	public synchronized void update(
			List<Board.BallSnapshot> smallBalls,
			Board.BallSnapshot playerBall,
			Board.BallSnapshot botBall,
			List<Hole> holes,
			GameSnapshot game,
			int framePerSec) {
		balls.clear();
		for (var ball : smallBalls) {
			balls.add(new BallViewInfo(ball.pos(), ball.radius()));
		}
		this.holes.clear();
		for (var hole : holes) {
			this.holes.add(new HoleViewInfo(hole.center(), hole.radius()));
		}
		player = playerBall == null ? null : new BallViewInfo(playerBall.pos(), playerBall.radius());
		bot = botBall == null ? null : new BallViewInfo(botBall.pos(), botBall.radius());
		this.game = game;
		this.framePerSec = framePerSec;
	}
	
	/**
	 * Gets the rendered small balls.
	 *
	 * @return copy of the rendered small-ball data
	 */
	public synchronized List<BallViewInfo> getBalls(){
		var copy = new ArrayList<BallViewInfo>();
		copy.addAll(balls);
		return copy;

	}

	/**
	 * Gets the current FPS value.
	 *
	 * @return latest measured frame rate
	 */
	public synchronized int getFramePerSec() {
		return framePerSec;
	}

	/**
	 * Gets the human cue ball render data.
	 *
	 * @return rendered human cue ball, or {@code null} when pocketed
	 */
	public synchronized BallViewInfo getPlayerBall() {
		return player;
	}

	/**
	 * Gets the bot cue ball render data.
	 *
	 * @return rendered bot cue ball, or {@code null} when pocketed
	 */
	public synchronized BallViewInfo getBotBall() {
		return bot;
	}

	/**
	 * Gets the logical game snapshot.
	 *
	 * @return latest logical game snapshot
	 */
	public synchronized GameSnapshot getGame() {
		return game;
	}

	/**
	 * Sets a human-owned shot preview.
	 *
	 * @param from cue-ball position
	 * @param to selected target point
	 * @param intensity shot strength used by the HUD label
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
		shotPreviews.put(player, new ShotPreviewInfo(from, to, intensity, player));
	}

	/**
	 * Removes any active shot preview.
	 */
	public synchronized void clearShotPreview() {
		shotPreviews.clear();
	}

	/**
	 * Removes the active shot preview for one player.
	 *
	 * @param player preview owner to clear
	 */
	public synchronized void clearShotPreview(Player player) {
		shotPreviews.remove(player);
	}

	/**
	 * Gets the active shot preview.
	 *
	 * @return human preview when present, otherwise bot preview, or {@code null}
	 *         when no preview is visible
	 */
	public synchronized ShotPreviewInfo getShotPreview() {
		var humanPreview = shotPreviews.get(Player.HUMAN);
		return humanPreview == null ? shotPreviews.get(Player.BOT) : humanPreview;
	}

	/**
	 * Gets the active shot preview for one player.
	 *
	 * @param player preview owner
	 * @return active player preview, or {@code null} when not visible
	 */
	public synchronized ShotPreviewInfo getShotPreview(Player player) {
		return shotPreviews.get(player);
	}

	/**
	 * Gets all active shot previews.
	 *
	 * @return copy of active shot previews
	 */
	public synchronized List<ShotPreviewInfo> getShotPreviews() {
		return new ArrayList<>(shotPreviews.values());
	}

	/**
	 * Gets the rendered holes.
	 *
	 * @return copy of rendered hole data
	 */
	public synchronized List<HoleViewInfo> getHoles() {
		return new ArrayList<HoleViewInfo>(holes);
	}
	
}
