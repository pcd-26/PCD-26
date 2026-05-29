package pcd.poool.model.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.Player;

/**
 * Mutable board state used by the physics loop.
 */
public class Board {

    public static record BallSnapshot(P2d pos, double radius) {}

    private final PhysicsEngine physicsEngine;
    private List<Ball> balls;
    private Ball playerBall;
    private Ball botBall;
    private Boundary bounds;
    private List<Hole> holes;
    private int pocketedSmallBalls;
    private int pendingScoredSmallBalls;
    private boolean playerBallPocketed;
    private boolean botBallPocketed;
    private Player activePlayer;
    private final Set<Ball> scoreEligibleBalls;
    
    public Board(){
        physicsEngine = new PhysicsEngine();
        balls = new ArrayList<>();
        holes = List.of();
        scoreEligibleBalls = new HashSet<>();
    }
    
    public void init(BoardConf conf) {
    	balls = new ArrayList<>(conf.getSmallBalls());
    	playerBall = conf.getPlayerBall();
        botBall = conf.getBotBall();
    	bounds = conf.getBoardBoundary();
        holes = new ArrayList<>(conf.getHoles());
        pocketedSmallBalls = 0;
        pendingScoredSmallBalls = 0;
        playerBallPocketed = false;
        botBallPocketed = false;
        activePlayer = null;
        scoreEligibleBalls.clear();
    }
    
    public synchronized void updateState(long dt) {
        physicsEngine.step(this, dt);
    }
    
    /**
     * Returns immutable rendering data instead of exposing mutable ball objects.
     */
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
    	if (playerBall == null || playerBallPocketed) {
    		return null;
    	}
    	return new BallSnapshot(playerBall.getPos(), playerBall.getRadius());
    }

    public synchronized BallSnapshot getBotBall() {
        if (botBall == null || botBallPocketed) {
            return null;
        }
        return new BallSnapshot(botBall.getPos(), botBall.getRadius());
    }

    public synchronized List<Hole> getHoles() {
        return Collections.unmodifiableList(new ArrayList<>(holes));
    }

    public synchronized int getPocketedSmallBalls() {
        return pocketedSmallBalls;
    }

    public synchronized int consumePendingScoredSmallBalls() {
        int scored = pendingScoredSmallBalls;
        pendingScoredSmallBalls = 0;
        return scored;
    }

    public synchronized boolean isPlayerBallPocketed() {
        return playerBallPocketed;
    }

    public synchronized boolean isBotBallPocketed() {
        return botBallPocketed;
    }

    public synchronized boolean areBallsMoving() {
        if (playerBall != null && !playerBallPocketed && playerBall.isMoving()) {
            return true;
        }
        if (botBall != null && !botBallPocketed && botBall.isMoving()) {
            return true;
        }
        return balls.stream().anyMatch(Ball::isMoving);
    }

    public synchronized void prepareTurn(Player player) {
        activePlayer = player;
        scoreEligibleBalls.clear();
        pendingScoredSmallBalls = 0;
    }

    public synchronized void kick(Player player, V2d velocity) {
        var cueBall = getCueBallEntity(player);
        if (cueBall != null) {
            cueBall.kick(velocity);
        }
    }
    
    public Boundary getBounds(){
        return bounds;
    }

    synchronized Ball getPlayerBallEntity() {
        return playerBallPocketed ? null : playerBall;
    }

    synchronized Ball getBotBallEntity() {
        return botBallPocketed ? null : botBall;
    }

    synchronized List<Ball> getSmallBallEntities() {
        return balls;
    }

    synchronized List<Ball> getCollisionBalls() {
        var allBalls = new ArrayList<Ball>();
        if (playerBall != null && !playerBallPocketed) {
            allBalls.add(playerBall);
        }
        if (botBall != null && !botBallPocketed) {
            allBalls.add(botBall);
        }
        allBalls.addAll(balls);
        return allBalls;
    }

    synchronized void recordCollision(Ball first, Ball second) {
        var activeCueBall = activePlayer == null ? null : getCueBallEntity(activePlayer);
        if (activeCueBall == null) {
            return;
        }
        if (first == activeCueBall && balls.contains(second)) {
            scoreEligibleBalls.add(second);
        } else if (second == activeCueBall && balls.contains(first)) {
            scoreEligibleBalls.add(first);
        }
    }

    synchronized void applyHoleInteractions() {
        if (holes.isEmpty()) {
            return;
        }
        /*
         * Game-level scoring will later decide who earns a point. The physics
         * layer only removes pocketed balls and records that the event happened.
         */
        if (playerBall != null && !playerBallPocketed && isInsideHole(playerBall)) {
            playerBallPocketed = true;
        }
        if (botBall != null && !botBallPocketed && isInsideHole(botBall)) {
            botBallPocketed = true;
        }
        var iterator = balls.iterator();
        while (iterator.hasNext()) {
            var ball = iterator.next();
            if (isInsideHole(ball)) {
                iterator.remove();
                pocketedSmallBalls++;
                if (scoreEligibleBalls.remove(ball)) {
                    pendingScoredSmallBalls++;
                }
            }
        }
    }

    private Ball getCueBallEntity(Player player) {
        if (player == Player.HUMAN) {
            return playerBallPocketed ? null : playerBall;
        }
        return botBallPocketed ? null : botBall;
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
