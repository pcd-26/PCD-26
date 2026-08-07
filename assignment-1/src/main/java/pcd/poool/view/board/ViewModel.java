package pcd.poool.view.board;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.game.GameSnapshot;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.Hole;

// Mutable snapshot read by the Swing renderer.
public class ViewModel {

	public static record BallViewInfo(P2d pos, double radius) {}
	public static record HoleViewInfo(P2d center, double radius) {}
	public static record ShotPreviewInfo(P2d from, P2d to, double intensity, Player player) {}

	private ArrayList<BallViewInfo> balls;
	private ArrayList<HoleViewInfo> holes;
	private BallViewInfo player;
	private BallViewInfo bot;
	private EnumMap<Player, ShotPreviewInfo> shotPreviews;
	private int framePerSec;
	private GameSnapshot game;

	public ViewModel() {
		balls = new ArrayList<BallViewInfo>();
		holes = new ArrayList<HoleViewInfo>();
		shotPreviews = new EnumMap<>(Player.class);
		framePerSec = 0;
	}

	public synchronized void update(Board board, int framePerSec) {
		balls.clear();
		for (var ball : board.getBalls()) {
			balls.add(new BallViewInfo(ball.pos(), ball.radius()));
		}
		holes.clear();
		for (var hole : board.getHoles()) {
			holes.add(new HoleViewInfo(hole.center(), hole.radius()));
		}
		this.framePerSec = framePerSec;
		var playerBall = board.getPlayerBall();
		player = playerBall == null ? null : new BallViewInfo(playerBall.pos(), playerBall.radius());
		var botBall = board.getBotBall();
		bot = botBall == null ? null : new BallViewInfo(botBall.pos(), botBall.radius());
	}

	public synchronized void update(Board board, GameSnapshot game, int framePerSec) {
		update(board, framePerSec);
		this.game = game;
	}

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

	public synchronized void setShotPreview(P2d from, P2d to, double intensity) {
		setShotPreview(from, to, intensity, Player.HUMAN);
	}

	public synchronized void setShotPreview(P2d from, P2d to, double intensity, Player player) {
		shotPreviews.put(player, new ShotPreviewInfo(from, to, intensity, player));
	}

	public synchronized void clearShotPreview() {
		shotPreviews.clear();
	}

	public synchronized void clearShotPreview(Player player) {
		shotPreviews.remove(player);
	}

	public synchronized ShotPreviewInfo getShotPreview() {
		var humanPreview = shotPreviews.get(Player.HUMAN);
		return humanPreview == null ? shotPreviews.get(Player.BOT) : humanPreview;
	}

	public synchronized ShotPreviewInfo getShotPreview(Player player) {
		return shotPreviews.get(player);
	}

	public synchronized List<ShotPreviewInfo> getShotPreviews() {
		return new ArrayList<>(shotPreviews.values());
	}

	public synchronized List<HoleViewInfo> getHoles() {
		return new ArrayList<HoleViewInfo>(holes);
	}
}
