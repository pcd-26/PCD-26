package pcd.poool.model.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<Player, Integer> pendingScoredSmallBalls;
    private boolean playerBallPocketed;
    private boolean botBallPocketed;
    private final Map<Ball, Player> lastDirectCueTouch;
    
    public Board(){
        physicsEngine = new PhysicsEngine();
        balls = new ArrayList<>();
        holes = List.of();
        pendingScoredSmallBalls = new EnumMap<>(Player.class);
        lastDirectCueTouch = new HashMap<>();
    }
    
    public void init(BoardConf conf) {
    	balls = new ArrayList<>(conf.getSmallBalls());
    	playerBall = conf.getPlayerBall();
        botBall = conf.getBotBall();
    	bounds = conf.getBoardBoundary();
        holes = new ArrayList<>(conf.getHoles());
        pocketedSmallBalls = 0;
        pendingScoredSmallBalls.clear();
        playerBallPocketed = false;
        botBallPocketed = false;
        lastDirectCueTouch.clear();
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
        int scored = pendingScoredSmallBalls.values().stream().mapToInt(Integer::intValue).sum();
        pendingScoredSmallBalls.clear();
        return scored;
    }

    public synchronized int consumePendingScoredSmallBalls(Player player) {
        var scored = pendingScoredSmallBalls.remove(player);
        return scored == null ? 0 : scored;
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

    public synchronized boolean canKick(Player player) {
        var cueBall = getCueBallEntity(player);
        return cueBall != null && !cueBall.isMoving();
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
        recordDirectCueTouch(first, second, Player.HUMAN);
        recordDirectCueTouch(first, second, Player.BOT);
        clearSmallBallScoringOnIndirectTouch(first, second);
    }

    private void recordDirectCueTouch(Ball first, Ball second, Player player) {
        var cueBall = getCueBallEntity(player);
        if (cueBall == null) {
            return;
        }
        if (first == cueBall && balls.contains(second)) {
            lastDirectCueTouch.put(second, player);
        } else if (second == cueBall && balls.contains(first)) {
            lastDirectCueTouch.put(first, player);
        }
    }

    private void clearSmallBallScoringOnIndirectTouch(Ball first, Ball second) {
        if (balls.contains(first) && balls.contains(second)) {
            lastDirectCueTouch.remove(first);
            lastDirectCueTouch.remove(second);
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
                var scorer = lastDirectCueTouch.remove(ball);
                if (scorer != null) {
                    pendingScoredSmallBalls.merge(scorer, 1, Integer::sum);
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
