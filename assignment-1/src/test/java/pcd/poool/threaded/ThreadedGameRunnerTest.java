package pcd.poool.threaded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.game.GameStatus;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.Boundary;
import pcd.poool.model.physics.common.Hole;
import pcd.poool.runtime.CommandReceiptSupport;

class ThreadedGameRunnerTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(2);
    private static final ThreadedGameRunner.Config FAST_WITHOUT_BOT =
            new ThreadedGameRunner.Config(5, false, 0, GameModel.StartupCountdown.disabled());

    /**
     * Verifies that the controller platform thread automatically runs and advances
     * the simulation steps in the shared GameModel over time.
     */
    @Test
    @Timeout(3)
    void controllerThreadAdvancesTheSharedGameModel() throws InterruptedException {
        try (var runner = new ThreadedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT)) {
            runner.start();

            var snapshot = runner.snapshots().awaitUntil(
                    state -> state.game().simulatedSteps() >= 2,
                    SHORT_TIMEOUT);

            assertTrue(snapshot.game().simulatedSteps() >= 2);
        }
    }

    /**
     * Verifies that a human shot command submitted from an external thread (like the Swing EDT)
     * is enqueued and executed asynchronously on the controller thread, causing the game status
     * to transition to BALLS_MOVING.
     */
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

    /**
     * Verifies that multiple concurrent threads submitting shot commands simultaneously
     * do not cause race conditions or lose command receipts.
     */
    @Test
    @Timeout(5)
    void concurrentHumanShotSubmissionsCompleteWithoutLostReceipts() throws InterruptedException {
        try (var runner = new ThreadedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT)) {
            runner.start();

            int producers = 6;
            int shotsPerProducer = 12;
            var startGate = new CountDownLatch(1);
            var readyGate = new CountDownLatch(producers);
            var receipts = Collections.synchronizedList(new ArrayList<CommandReceiptSupport<Boolean>>());
            ExecutorService executor = Executors.newFixedThreadPool(producers);

            try {
                for (int i = 0; i < producers; i++) {
                    executor.submit(() -> {
                        readyGate.countDown();
                        try {
                            startGate.await();
                            for (int j = 0; j < shotsPerProducer; j++) {
                                receipts.add(runner.shootHuman(new V2d(0, 0)));
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }

                assertTrue(readyGate.await(1, TimeUnit.SECONDS));
                startGate.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

                int completed = 0;
                for (var receipt : receipts) {
                    assertFalse(receipt.await(SHORT_TIMEOUT));
                    completed++;
                }

                assertEquals(producers * shotsPerProducer, completed);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * Verifies that the bot agent runs in its own thread as a separate active component
     * and automatically triggers a shot when it is eligible to shoot.
     */
    @Test
    @Timeout(3)
    void botAgentSubmitsShotsFromASeparateActiveComponent() throws InterruptedException {
        var config = new ThreadedGameRunner.Config(5, true, 0, GameModel.StartupCountdown.disabled());
        try (var runner = new ThreadedGameRunner(new DirectScoringConf(), config)) {
            runner.start();

            var snapshot = runner.snapshots().awaitUntil(
                    state -> state.game().status() == GameStatus.BALLS_MOVING,
                    SHORT_TIMEOUT);

            assertFalse(snapshot.game().botCanShoot());
        }
    }

    /**
     * Verifies that the game snapshot contains a non-zero shot preview vector for the bot
     * whenever the bot is ready to execute a shot.
     */
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

    /**
     * Verifies that any pending commands that have not been executed yet are correctly
     * completed as rejected (i.e. returning false) when the runner is closed/shut down.
     */
    @Test
    @Timeout(3)
    void rejectedCommandsCompleteAfterShutdown() throws InterruptedException {
        var runner = new ThreadedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT);
        runner.start();

        int producers = 4;
        int shotsPerProducer = 20;
        var startGate = new CountDownLatch(1);
        var readyGate = new CountDownLatch(producers);
        var receipts = Collections.synchronizedList(new ArrayList<CommandReceiptSupport<Boolean>>());
        ExecutorService executor = Executors.newFixedThreadPool(producers);

        try {
            for (int i = 0; i < producers; i++) {
                executor.submit(() -> {
                    readyGate.countDown();
                    try {
                        startGate.await();
                        for (int j = 0; j < shotsPerProducer; j++) {
                            receipts.add(runner.shootHuman(new V2d(0, 0)));
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(readyGate.await(1, TimeUnit.SECONDS));
            startGate.countDown();
            runner.close();

            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

            int completed = 0;
            for (var receipt : receipts) {
                assertFalse(receipt.await(SHORT_TIMEOUT));
                completed++;
            }

            assertEquals(producers * shotsPerProducer, completed);
            assertFalse(runner.isRunning());
        } finally {
            executor.shutdownNow();
            runner.close();
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
}
