package pcd.poool.view.board;

import java.util.ArrayList;
import java.util.List;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.physics.Board;

public class ViewModel {

	public static record BallViewInfo(P2d pos, double radius) {}

	private ArrayList<BallViewInfo> balls;
	private BallViewInfo player;
	private int framePerSec;
	
	public ViewModel() {
		balls = new ArrayList<BallViewInfo>();
		framePerSec = 0;
	}
	
	public synchronized void update(Board board, int framePerSec) {
		balls.clear();
		for (var b: board.getBalls()) {
			balls.add(new BallViewInfo(b.pos(), b.radius()));
		}
		this.framePerSec = framePerSec;
		var p = board.getPlayerBall();
		player = p == null ? null : new BallViewInfo(p.pos(), p.radius());
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
	
}
