package pcd.poool.taskbased;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;
import pcd.poool.runtime.CommandReceiptSupport;
import pcd.poool.runtime.GameRuntimeConfig;

class TaskBasedGameRunnerTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(2);
    private static final GameRuntimeConfig FAST_WITHOUT_BOT =
            new GameRuntimeConfig(5, false, 0, GameModel.StartupCountdown.disabled());

    @Test
    @Timeout(3)
    void controllerTaskAdvancesTheSharedGameModel() throws InterruptedException {
        try (var runner = new TaskBasedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT)) {
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
        try (var runner = new TaskBasedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT)) {
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
    @Timeout(5)
    void concurrentHumanShotSubmissionsCompleteWithoutLostReceipts() throws InterruptedException {
        try (var runner = new TaskBasedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT)) {
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

    @Test
    @Timeout(3)
    void rejectedCommandsCompleteAfterShutdown() throws InterruptedException {
        var runner = new TaskBasedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT);
        runner.start();

        int producers = 4;
        int shotsPerProducer = 20;
        var startGate = new CountDownLatch(1);
        var firstBatchGate = new CountDownLatch(producers);
        var readyGate = new CountDownLatch(producers);
        var receipts = Collections.synchronizedList(new ArrayList<CommandReceiptSupport<Boolean>>());
        ExecutorService executor = Executors.newFixedThreadPool(producers);

        try {
            for (int i = 0; i < producers; i++) {
                executor.submit(() -> {
                    readyGate.countDown();
                    try {
                        receipts.add(runner.shootHuman(new V2d(0, 0)));
                        firstBatchGate.countDown();
                        startGate.await();
                        for (int j = 1; j < shotsPerProducer; j++) {
                            receipts.add(runner.shootHuman(new V2d(0, 0)));
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(readyGate.await(1, TimeUnit.SECONDS));
            assertTrue(firstBatchGate.await(1, TimeUnit.SECONDS));
            runner.close();
            startGate.countDown();

            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

            assertTrue(receipts.size() >= producers);
            for (var receipt : receipts) {
                assertFalse(receipt.await(SHORT_TIMEOUT));
            }

            assertFalse(runner.isRunning());
        } finally {
            executor.shutdownNow();
            runner.close();
        }
    }

    @Test
    @Timeout(3)
    void repeatedStopIsSafe() {
        var runner = new TaskBasedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT);
        runner.start();

        runner.close();
        runner.close();

        assertFalse(runner.isRunning());
    }

    @Test
    void shutdownPreventsNewCommands() throws InterruptedException {
        var runner = new TaskBasedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT);
        runner.start();
        runner.close();

        var receipt = runner.shootHuman(new V2d(0.1, 0.0));
        assertFalse(receipt.await(SHORT_TIMEOUT));
    }

    @Test
    @Timeout(3)
    void botAgentSubmitsShotsFromASeparateTask() throws InterruptedException {
        var config = new GameRuntimeConfig(5, true, 0, GameModel.StartupCountdown.disabled());
        try (var runner = new TaskBasedGameRunner(new DirectScoringConf(), config)) {
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
        try (var runner = new TaskBasedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT)) {
            runner.start();

            var snapshot = runner.snapshots().awaitUntil(
                    state -> state.game().botCanShoot(),
                    SHORT_TIMEOUT);

            assertTrue(snapshot.botPreviewShot().abs() > 0.0);
        }
    }

    @Test
    @Timeout(5)
    void taskBasedRunnerAdvancesSimulationAndSurvivesHighCommandLoad() throws InterruptedException {
        try (var runner = new TaskBasedGameRunner(new DirectScoringConf(), FAST_WITHOUT_BOT)) {
            runner.start();

            int producers = 6;
            int shotsPerProducer = 30;
            var startGate = new CountDownLatch(1);
            var readyGate = new CountDownLatch(producers);
            var executor = Executors.newFixedThreadPool(producers);

            try {
                for (int i = 0; i < producers; i++) {
                    executor.submit(() -> {
                        readyGate.countDown();
                        try {
                            startGate.await();
                            for (int j = 0; j < shotsPerProducer; j++) {
                                runner.shootHuman(new V2d(0, 0));
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

                var snapshot = runner.snapshots().awaitUntil(
                        state -> state.game().simulatedSteps() >= 4,
                        SHORT_TIMEOUT);

                assertTrue(snapshot.game().simulatedSteps() >= 4);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void taskFailuresArePropagatedToTheCaller() throws InterruptedException {
        try (var runner = new TaskBasedGameRunner(
                new DirectScoringConf(),
                FAST_WITHOUT_BOT,
                new FailingPhysicsEngine())) {
            runner.start();

            runner.snapshots().awaitUntil(state -> runner.failure() != null, SHORT_TIMEOUT);

            var failure = assertThrows(IllegalStateException.class, runner::snapshot);
            assertTrue(failure.getMessage().contains("task-based game runner failed"));
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

    private static class FailingPhysicsEngine extends TaskBasedPhysicsEngine {

        FailingPhysicsEngine() {
            super(1);
        }

        @Override
        public void step(pcd.poool.model.physics.common.Board board, long elapsedMillis) {
            throw new IllegalStateException("Injected task failure");
        }
    }
}
