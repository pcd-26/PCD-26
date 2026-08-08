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
import pcd.poool.model.physics.sequential.SequentialPhysicsEngine;

// Mutable board state used by the physics loop.
public class Board {

    public static record BallSnapshot(P2d pos, double radius) {}

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

    // Creates an empty board using the default sequential physics engine.
    public Board() {
        this(new SequentialPhysicsEngine());
    }

    // Creates an empty board using the supplied physics stepping strategy.
    public Board(PhysicsStepper physicsEngine) {
        if (physicsEngine == null) {
            throw new IllegalArgumentException("physicsEngine must not be null");
        }
        this.physicsEngine = physicsEngine;
        balls = new ArrayList<>();
        holes = List.of();
        pendingScoredSmallBalls = new EnumMap<>(Player.class);
        lastDirectCueTouch = new HashMap<>();
    }

    // Reinitializes this board from a configuration.
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

    // Advances the board physics by the given elapsed time.
    public synchronized void updateState(long dt) {
        physicsEngine.step(this, dt);
    }

    // Returns immutable rendering data instead of exposing mutable ball objects.
    public synchronized List<BallSnapshot> getBalls() {
        if (balls == null) {
            return Collections.emptyList();
        }
        var snapshots = new ArrayList<BallSnapshot>();
        for (var ball : balls) {
            snapshots.add(new BallSnapshot(ball.getPos(), ball.getRadius()));
        }
        return Collections.unmodifiableList(snapshots);
    }

    // Gets the human cue-ball snapshot.
    public synchronized BallSnapshot getPlayerBall() {
        if (playerBall == null || playerBallPocketed) {
            return null;
        }
        return new BallSnapshot(playerBall.getPos(), playerBall.getRadius());
    }

    // Gets the bot cue-ball snapshot.
    public synchronized BallSnapshot getBotBall() {
        if (botBall == null || botBallPocketed) {
            return null;
        }
        return new BallSnapshot(botBall.getPos(), botBall.getRadius());
    }

    // Gets the configured holes.
    public synchronized List<Hole> getHoles() {
        return Collections.unmodifiableList(new ArrayList<>(holes));
    }

    // Gets the total number of pocketed small balls.
    public synchronized int getPocketedSmallBalls() {
        return pocketedSmallBalls;
    }

    // Consumes all pending score events regardless of player. Legacy compatibility helper.
    public synchronized int consumePendingScoredSmallBalls() {
        int scored = pendingScoredSmallBalls.values().stream().mapToInt(Integer::intValue).sum();
        pendingScoredSmallBalls.clear();
        return scored;
    }

    // Consumes pending small-ball score events for one player.
    public synchronized int consumePendingScoredSmallBalls(Player player) {
        var scored = pendingScoredSmallBalls.remove(player);
        return scored == null ? 0 : scored;
    }

    // Checks whether the human cue ball has been pocketed.
    public synchronized boolean isPlayerBallPocketed() {
        return playerBallPocketed;
    }

    // Checks whether the bot cue ball has been pocketed.
    public synchronized boolean isBotBallPocketed() {
        return botBallPocketed;
    }

    // Checks whether any active ball is moving.
    public synchronized boolean areBallsMoving() {
        if (playerBall != null && !playerBallPocketed && playerBall.isMoving()) {
            return true;
        }
        if (botBall != null && !botBallPocketed && botBall.isMoving()) {
            return true;
        }
        return balls.stream().anyMatch(Ball::isMoving);
    }

    // Checks whether a player's cue ball can currently be kicked.
    public synchronized boolean canKick(Player player) {
        var cueBall = getCueBallEntity(player);
        return cueBall != null && !cueBall.isMoving();
    }

    // Assigns a new velocity to a player's cue ball if it is still on the board.
    public synchronized void kick(Player player, V2d velocity) {
        var cueBall = getCueBallEntity(player);
        if (cueBall != null) {
            cueBall.kick(velocity);
        }
    }

    // Gets the board boundary.
    public Boundary getBounds() {
        return bounds;
    }

    // Gets the mutable human cue-ball entity for the physics engine.
    public synchronized Ball getPlayerBallEntity() {
        return playerBallPocketed ? null : playerBall;
    }

    // Gets the mutable bot cue-ball entity for the physics engine.
    public synchronized Ball getBotBallEntity() {
        return botBallPocketed ? null : botBall;
    }

    // Gets the mutable list of small balls for the physics engine.
    public synchronized List<Ball> getSmallBallEntities() {
        return balls;
    }

    // Copies the active balls that may collide into a caller-provided list.
    public synchronized void fillCandidateCollisionBalls(List<Ball> target) {
        target.clear();
        if (playerBall != null && !playerBallPocketed) {
            target.add(playerBall);
        }
        if (botBall != null && !botBallPocketed) {
            target.add(botBall);
        }
        target.addAll(balls);
    }

    // Gets all active balls that participate in collision detection.
    public synchronized List<Ball> getCandidateCollisionBalls() {
        var allBalls = new ArrayList<Ball>();
        fillCandidateCollisionBalls(allBalls);
        return allBalls;
    }

    // Records a collision so the board can track direct cue-ball contact.
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

    // Applies pocketing directly by inspecting the current board state.
    public synchronized void applyHoleInteractions() {
        if (holes.isEmpty()) {
            return;
        }
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
                    int updatedScore = pendingScoredSmallBalls.getOrDefault(scorer, 0) + 1;
                    pendingScoredSmallBalls.put(scorer, updatedScore);
                }
            }
        }
    }

    // Applies pocketing using a deterministic summary computed elsewhere.
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
                iterator.remove();
                pocketedSmallBalls++;
                var scorer = lastDirectCueTouch.remove(ball);
                if (scorer != null) {
                    int updatedScore = pendingScoredSmallBalls.getOrDefault(scorer, 0) + 1;
                    pendingScoredSmallBalls.put(scorer, updatedScore);
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
