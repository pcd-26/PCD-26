package pcd.poool.model.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.Boundary;
import pcd.poool.model.physics.common.Hole;
import pcd.poool.model.physics.common.PhysicsDefaults;

class GameModelTest {

    @Test
    void humanScoresWhenOwnCueBallDirectlyPocketsSmallBall() {
        var game = new GameModel(new DirectScoringConf());

        assertTrue(game.shootHuman(new V2d(1.6, 0)));
        runUntilNotMoving(game, 400);

        var snapshot = game.snapshot();
        assertEquals(1, snapshot.humanScore());
        assertEquals(0, snapshot.botScore());
        assertTrue(snapshot.humanCanShoot());
        assertTrue(snapshot.botCanShoot());
        assertEquals(GameStatus.RUNNING, snapshot.status());
    }

    @Test
    void humanAndBotCanShootIndependentlyWhenTheirCueBallsAreStopped() {
        var game = new GameModel(new DirectScoringConf());

        assertTrue(game.shootHuman(new V2d(1.6, 0)));
        assertTrue(game.shootBot());
    }

    @Test
    void pocketingHumanCueBallImmediatelyGivesTheWinToBot() {
        var game = new GameModel(new HumanCueAlreadyInHoleConf());

        game.step(PhysicsDefaults.FIXED_STEP_MILLIS);

        var snapshot = game.snapshot();
        assertEquals(GameStatus.FINISHED, snapshot.status());
        assertEquals(Player.BOT, snapshot.winner());
        assertEquals(GameOverReason.HUMAN_CUE_BALL_POCKETED, snapshot.gameOverReason());
    }

    @Test
    void exposesBaselineStepMetrics() {
        var game = new GameModel(new DirectScoringConf());

        game.step(PhysicsDefaults.FIXED_STEP_MILLIS);

        assertEquals(1, game.snapshot().simulatedSteps());
        assertTrue(game.snapshot().averageStepMillis() >= 0.0);
    }

    @Test
    void botShotCanBePreviewedBeforeItIsExecuted() {
        var game = new GameModel(new DirectScoringConf());
        game.shootHuman(new V2d(1.6, 0));
        runUntilNotMoving(game, 400);

        var preview = game.previewBotShot();

        assertTrue(game.snapshot().botCanShoot());
        assertTrue(preview.abs() > 0.0);
    }

    @Test
    void smallBallPocketedAfterSmallBallCollisionDoesNotScore() {
        var game = new GameModel(new IndirectPocketConf());

        assertTrue(game.shootHuman(new V2d(1.6, 0)));
        runUntilNotMoving(game, 500);

        assertEquals(0, game.snapshot().humanScore());
    }

    private void runUntilNotMoving(GameModel game, int maxSteps) {
        for (int i = 0; i < maxSteps && game.snapshot().status() == GameStatus.BALLS_MOVING; i++) {
            game.step(PhysicsDefaults.FIXED_STEP_MILLIS);
        }
    }

    private static class DirectScoringConf implements BoardConf {

        @Override
        public Boundary getBoardBoundary() {
            return new Boundary(-1, -1, 1, 1);
        }

        @Override
        public Ball getPlayerBall() {
            return new Ball(new P2d(-0.25, 0), 0.05, 1.0, new V2d(0, 0));
        }

        @Override
        public Ball getBotBall() {
            return new Ball(new P2d(0, -0.75), 0.05, 1.0, new V2d(0, 0));
        }

        @Override
        public List<Ball> getSmallBalls() {
            return List.of(
                    new Ball(new P2d(0.05, 0), 0.05, 1.0, new V2d(0, 0)),
                    new Ball(new P2d(-0.75, -0.75), 0.05, 1.0, new V2d(0, 0)));
        }

        @Override
        public List<Hole> getHoles() {
            return List.of(new Hole(new P2d(0.85, 0), 0.12));
        }
    }

    private static class HumanCueAlreadyInHoleConf extends DirectScoringConf {

        @Override
        public Ball getPlayerBall() {
            return new Ball(new P2d(0.85, 0), 0.05, 1.0, new V2d(0, 0));
        }
    }

    private static class IndirectPocketConf implements BoardConf {

        @Override
        public Boundary getBoardBoundary() {
            return new Boundary(-1, -1, 1, 1);
        }

        @Override
        public Ball getPlayerBall() {
            return new Ball(new P2d(-0.45, 0), 0.05, 1.0, new V2d(0, 0));
        }

        @Override
        public Ball getBotBall() {
            return new Ball(new P2d(0, -0.75), 0.05, 1.0, new V2d(0, 0));
        }

        @Override
        public List<Ball> getSmallBalls() {
            return List.of(
                    new Ball(new P2d(-0.10, 0), 0.05, 1.0, new V2d(0, 0)),
                    new Ball(new P2d(0.28, 0), 0.05, 1.0, new V2d(-0.25, 0)));
        }

        @Override
        public List<Hole> getHoles() {
            return List.of(new Hole(new P2d(0.85, 0), 0.12));
        }
    }
}
