package pcd.poool.model.physics;

import java.util.*;
import pcd.poool.model.common.math.P2d;

/**
 * Mutable board state used by the physics loop.
 */
public class Board {

    public static record BallSnapshot(P2d pos, double radius) {}

    private List<Ball> balls;    
    private Ball playerBall;
    private Boundary bounds;
    
    public Board(){} 
    
    public void init(BoardConf conf) {
    	balls = conf.getSmallBalls();    	
    	playerBall = conf.getPlayerBall(); 
    	bounds = conf.getBoardBoundary();
    }
    
    public synchronized void updateState(long dt) {

    	playerBall.updateState(dt, this);
    	
    	for (var b: balls) {
    		b.updateState(dt, this);
    	}       	
    	
    	for (int i = 0; i < balls.size() - 1; i++) {
            for (int j = i + 1; j < balls.size(); j++) {
                Ball.resolveCollision(balls.get(i), balls.get(j));
            }
        }
    	for (var b: balls) {
    		Ball.resolveCollision(playerBall, b);
    	} 
    	   	    	
    }
    
    public synchronized List<BallSnapshot> getBalls(){
    	if (balls == null) {
    		return Collections.emptyList();
    	}
    	var snapshots = new ArrayList<BallSnapshot>();
    	for (var ball: balls) {
    		snapshots.add(new BallSnapshot(ball.getPos(), ball.getRadius()));
    	}
    	return Collections.unmodifiableList(snapshots);
    }
    
    public synchronized BallSnapshot getPlayerBall() {
    	if (playerBall == null) {
    		return null;
    	}
    	return new BallSnapshot(playerBall.getPos(), playerBall.getRadius());
    }
    
    public Boundary getBounds(){
        return bounds;
    }
}
