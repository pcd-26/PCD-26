package pcd.poool.threaded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameStatus;
import pcd.poool.model.physics.Ball;
import pcd.poool.model.physics.BoardConf;
import pcd.poool.model.physics.Boundary;
import pcd.poool.model.physics.Hole;

class ThreadedGameRunnerTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(2);
    private static final ThreadedGameRunner.Config FAST_WITHOUT_BOT =
            new ThreadedGameRunner.Config(5, false, 0);

    @Test
    @Timeout(3)
    void controllerThreadAdvancesTheSequentialGameModel() throws InterruptedException {
        try (var runner = new ThreadedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT)) {
            runner.start();

            var snapshot = runner.snapshots().awaitUntil(
                    state -> state.game().simulatedSteps() >= 2,
                    SHORT_TIMEOUT);

            assertTrue(snapshot.game().simulatedSteps() >= 2);
        }
    }

    @Test
    @Timeout(3)
    void humanShotIsExecutedAsAnAsynchronousCommand() throws InterruptedException {
        try (var runner = new ThreadedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT)) {
            runner.start();

            var accepted = runner.shootHuman(new V2d(1.6, 0)).await(SHORT_TIMEOUT);
            var snapshot = runner.snapshots().awaitUntil(
                    state -> state.game().status() == GameStatus.BALLS_MOVING,
                    SHORT_TIMEOUT);

            assertTrue(accepted);
            assertFalse(snapshot.game().humanCanShoot());
        }
    }

    @Test
    @Timeout(3)
    void botAgentSubmitsShotsFromASeparateActiveComponent() throws InterruptedException {
        var config = new ThreadedGameRunner.Config(5, true, 0);
        try (var runner = new ThreadedGameRunner(new DirectScoringConf(), config)) {
            runner.start();

            var snapshot = runner.snapshots().awaitUntil(
                    state -> state.game().status() == GameStatus.BALLS_MOVING,
                    SHORT_TIMEOUT);

            assertFalse(snapshot.game().botCanShoot());
        }
    }

    @Test
    @Timeout(3)
    void snapshotExposesBotPreviewWhenBotCanShoot() throws InterruptedException {
        try (var runner = new ThreadedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT)) {
            runner.start();

            var snapshot = runner.snapshots().awaitUntil(
                    state -> state.game().botCanShoot(),
                    SHORT_TIMEOUT);

            assertTrue(snapshot.botPreviewShot().abs() > 0.0);
        }
    }

    @Test
    @Timeout(3)
    void rejectedCommandsCompleteAfterShutdown() throws InterruptedException {
        var runner = new ThreadedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT);
        runner.start();
        runner.close();

        assertFalse(runner.isRunning());
        assertEquals(false, runner.shootHuman(new V2d(1.6, 0)).await(SHORT_TIMEOUT));
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
}
