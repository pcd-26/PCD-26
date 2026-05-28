package pcd.poool.model.physics;

import java.util.*;
import pcd.poool.model.common.math.P2d;

/**
 * Mutable board state used by the physics loop.
 */
public class Board {

    public static record BallSnapshot(P2d pos, double radius) {}

    private final PhysicsEngine physicsEngine;
    private List<Ball> balls;
    private Ball playerBall;
    private Boundary bounds;
    private List<Hole> holes;
    private int pocketedSmallBalls;
    private boolean playerBallPocketed;
    
    public Board(){
        physicsEngine = new PhysicsEngine();
        balls = new ArrayList<>();
        holes = List.of();
    }
    
    public void init(BoardConf conf) {
    	balls = new ArrayList<>(conf.getSmallBalls());
    	playerBall = conf.getPlayerBall();
    	bounds = conf.getBoardBoundary();
        holes = new ArrayList<>(conf.getHoles());
        pocketedSmallBalls = 0;
        playerBallPocketed = false;
    }
    
    public synchronized void updateState(long dt) {
        physicsEngine.step(this, dt);
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

    public synchronized List<Hole> getHoles() {
        return Collections.unmodifiableList(new ArrayList<>(holes));
    }

    public synchronized int getPocketedSmallBalls() {
        return pocketedSmallBalls;
    }

    public synchronized boolean isPlayerBallPocketed() {
        return playerBallPocketed;
    }

    public synchronized boolean areBallsMoving() {
        if (playerBall != null && playerBall.isMoving()) {
            return true;
        }
        return balls.stream().anyMatch(Ball::isMoving);
    }
    
    public Boundary getBounds(){
        return bounds;
    }

    synchronized Ball getPlayerBallEntity() {
        return playerBallPocketed ? null : playerBall;
    }

    synchronized List<Ball> getSmallBallEntities() {
        return balls;
    }

    synchronized List<Ball> getCollisionBalls() {
        var allBalls = new ArrayList<Ball>();
        if (playerBall != null && !playerBallPocketed) {
            allBalls.add(playerBall);
        }
        allBalls.addAll(balls);
        return allBalls;
    }

    synchronized void applyHoleInteractions() {
        if (holes.isEmpty()) {
            return;
        }
        if (playerBall != null && !playerBallPocketed && isInsideHole(playerBall)) {
            playerBallPocketed = true;
        }
        var iterator = balls.iterator();
        while (iterator.hasNext()) {
            if (isInsideHole(iterator.next())) {
                iterator.remove();
                pocketedSmallBalls++;
            }
        }
    }

    private boolean isInsideHole(Ball ball) {
        for (var hole : holes) {
            if (hole.contains(ball.getPos())) {
                return true;
            }
        }
        return false;
    }
}
