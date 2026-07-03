package pcd.poool.model.physics.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.sequential.PhysicsEngine;

/**
 * Mutable board state used by the physics loop.
 *
 * <p>The board owns the physical entities and records low-level events needed
 * by game rules, such as cue balls being pocketed and small balls that are
 * eligible to score. Higher-level lifecycle decisions remain in
 * {@code GameModel}.
 */
public class Board {

    /**
     * Immutable ball data used by rendering and tests.
     *
     * @param pos ball center position
     * @param radius ball radius
     */
    public static record BallSnapshot(P2d pos, double radius) {}

    /**
     * Immutable hole-interaction summary produced by a physics coordinator.
     *
     * @param playerBallPocketed whether the human cue ball entered a hole
     * @param botBallPocketed whether the bot cue ball entered a hole
     * @param pocketedSmallBalls small balls that entered holes, in deterministic order
     */
    public static record HoleInteractions(
            boolean playerBallPocketed,
            boolean botBallPocketed,
            List<Ball> pocketedSmallBalls) {}

    private final PhysicsStepper physicsEngine;
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
    
    /**
     * Creates an empty board. Call {@link #init(BoardConf)} before using it in
     * a game or benchmark.
     */
    public Board(){
        this(new PhysicsEngine());
    }

    /**
     * Creates an empty board using the supplied physics stepping strategy.
     *
     * @param physicsEngine physics strategy used by {@link #updateState(long)}
     */
    public Board(PhysicsStepper physicsEngine){
        if (physicsEngine == null) {
            throw new IllegalArgumentException("physicsEngine must not be null");
        }
        this.physicsEngine = physicsEngine;
        balls = new ArrayList<>();
        holes = List.of();
        pendingScoredSmallBalls = new EnumMap<>(Player.class);
        lastDirectCueTouch = new HashMap<>();
    }
    
    /**
     * Reinitializes this board from a configuration.
     *
     * @param conf board layout and initial ball entities
     */
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
    
    /**
     * Advances the board physics by the given elapsed time.
     *
     * @param dt elapsed time in milliseconds
     */
    public synchronized void updateState(long dt) {
        // The actual stepping policy is injected, so the board only owns the
        // mutable state and the monitor around it.
        physicsEngine.step(this, dt);
    }
    
    /**
     * Returns immutable rendering data instead of exposing mutable ball objects.
     *
     * @return immutable snapshots of all small balls currently on the board
     */
    public synchronized List<BallSnapshot> getBalls(){
        if (balls == null) {
            return Collections.emptyList();
        }
        // Rendering gets copies, not live Ball references.
        var snapshots = new ArrayList<BallSnapshot>();
        for (var ball: balls) {
            snapshots.add(new BallSnapshot(ball.getPos(), ball.getRadius()));
        }
        return Collections.unmodifiableList(snapshots);
    }
    
    /**
     * Gets the human cue-ball snapshot.
     *
     * @return immutable human cue-ball snapshot, or {@code null} when pocketed
     */
    public synchronized BallSnapshot getPlayerBall() {
    	if (playerBall == null || playerBallPocketed) {
    		return null;
    	}
    	return new BallSnapshot(playerBall.getPos(), playerBall.getRadius());
    }

    /**
     * Gets the bot cue-ball snapshot.
     *
     * @return immutable bot cue-ball snapshot, or {@code null} when pocketed
     */
    public synchronized BallSnapshot getBotBall() {
        if (botBall == null || botBallPocketed) {
            return null;
        }
        return new BallSnapshot(botBall.getPos(), botBall.getRadius());
    }

    /**
     * Gets the configured holes.
     *
     * @return immutable copy of the configured holes
     */
    public synchronized List<Hole> getHoles() {
        return Collections.unmodifiableList(new ArrayList<>(holes));
    }

    /**
     * Gets the total number of pocketed small balls.
     *
     * @return total number of small balls removed through holes
     */
    public synchronized int getPocketedSmallBalls() {
        return pocketedSmallBalls;
    }

    /**
     * Consumes all pending score events regardless of player.
     *
     * <p>This legacy aggregate form is kept for tests and compatibility. Game
     * rules should prefer {@link #consumePendingScoredSmallBalls(Player)}.
     *
     * @return number of pending score events across all players
     */
    public synchronized int consumePendingScoredSmallBalls() {
        int scored = pendingScoredSmallBalls.values().stream().mapToInt(Integer::intValue).sum();
        pendingScoredSmallBalls.clear();
        return scored;
    }

    /**
     * Consumes pending small-ball score events for one player.
     *
     * <p>A score is pending only when that player's cue ball directly touched
     * the small ball and the ball reached a hole before any small-small
     * collision invalidated that direct cause.
     *
     * @param player score owner
     * @return number of newly scored balls for the player
     */
    public synchronized int consumePendingScoredSmallBalls(Player player) {
        var scored = pendingScoredSmallBalls.remove(player);
        return scored == null ? 0 : scored;
    }

    /**
     * Checks whether the human cue ball has been pocketed.
     *
     * @return whether the human cue ball has entered a hole
     */
    public synchronized boolean isPlayerBallPocketed() {
        return playerBallPocketed;
    }

    /**
     * Checks whether the bot cue ball has been pocketed.
     *
     * @return whether the bot cue ball has entered a hole
     */
    public synchronized boolean isBotBallPocketed() {
        return botBallPocketed;
    }

    /**
     * Checks whether any active ball is moving.
     *
     * @return whether any non-pocketed ball is still moving
     */
    public synchronized boolean areBallsMoving() {
        if (playerBall != null && !playerBallPocketed && playerBall.isMoving()) {
            return true;
        }
        if (botBall != null && !botBallPocketed && botBall.isMoving()) {
            return true;
        }
        return balls.stream().anyMatch(Ball::isMoving);
    }

    /**
     * Checks whether a player's cue ball can currently be kicked.
     *
     * @param player cue-ball owner
     * @return whether the player's cue ball exists and is stopped
     */
    public synchronized boolean canKick(Player player) {
        var cueBall = getCueBallEntity(player);
        return cueBall != null && !cueBall.isMoving();
    }

    /**
     * Assigns a new velocity to a player's cue ball if it is still on the board.
     *
     * @param player cue-ball owner
     * @param velocity new cue-ball velocity
     */
    public synchronized void kick(Player player, V2d velocity) {
        var cueBall = getCueBallEntity(player);
        if (cueBall != null) {
            cueBall.kick(velocity);
        }
    }
    
    /**
     * Gets the board boundary.
     *
     * @return rectangular board boundary
     */
    public Boundary getBounds(){
        return bounds;
    }

    public synchronized Ball getPlayerBallEntity() {
        return playerBallPocketed ? null : playerBall;
    }

    public synchronized Ball getBotBallEntity() {
        return botBallPocketed ? null : botBall;
    }

    public synchronized List<Ball> getSmallBallEntities() {
        return balls;
    }

    public synchronized List<Ball> getCollisionBalls() {
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

    public synchronized void recordCollision(Ball first, Ball second) {
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

    public synchronized void applyHoleInteractions() {
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
                // Hole removal is serialized here so score bookkeeping stays
                // consistent with the board's mutable list.
                iterator.remove();
                pocketedSmallBalls++;
                var scorer = lastDirectCueTouch.remove(ball);
                if (scorer != null) {
                    pendingScoredSmallBalls.merge(scorer, 1, Integer::sum);
                }
            }
        }
    }

    /**
     * Applies a hole-interaction summary computed by a task-based coordinator.
     *
     * <p>The provided small-ball list must be ordered deterministically by the
     * coordinator, with task order first and ball order inside each task.
     *
     * @param interactions detected pocketed entities for the current physics tick
     */
    public synchronized void applyHoleInteractions(HoleInteractions interactions) {
        if (holes.isEmpty()) {
            return;
        }

        if (interactions.playerBallPocketed() && playerBall != null && !playerBallPocketed) {
            playerBallPocketed = true;
        }
        if (interactions.botBallPocketed() && botBall != null && !botBallPocketed) {
            botBallPocketed = true;
        }

        if (interactions.pocketedSmallBalls().isEmpty()) {
            return;
        }

        var pocketed = new LinkedHashSet<>(interactions.pocketedSmallBalls());
        var iterator = balls.iterator();
        while (iterator.hasNext()) {
            var ball = iterator.next();
            if (pocketed.contains(ball)) {
                // The coordinator already decided the pocketed set, so this is
                // just the commit phase.
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
