package pcd.poool.model.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pcd.poool.model.physics.config.MinimalBoardConf;

class ThreadedPhysicsEngineTest {

    @Test
    @Timeout(3)
    void threadedPhysicsPreservesSequentialOutcomeOnDeterministicConfiguration() {
        var sequentialBoard = new Board();
        sequentialBoard.init(new MinimalBoardConf());

        try (var threadedEngine = new ThreadedPhysicsEngine(2)) {
            var threadedBoard = new Board(threadedEngine);
            threadedBoard.init(new MinimalBoardConf());

            for (int i = 0; i < 60; i++) {
                sequentialBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                threadedBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }

            assertEquals(sequentialBoard.getBalls().size(), threadedBoard.getBalls().size());
            assertEquals(sequentialBoard.getPlayerBall(), threadedBoard.getPlayerBall());
            assertEquals(sequentialBoard.getBotBall(), threadedBoard.getBotBall());
            assertEquals(sequentialBoard.getPocketedSmallBalls(), threadedBoard.getPocketedSmallBalls());
        }
    }

    @Test
    @Timeout(3)
    void workerThreadsCanBeClosedAfterUse() {
        var engine = new ThreadedPhysicsEngine(2);
        var board = new Board(engine);
        board.init(new MinimalBoardConf());

        board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
        engine.close();

        assertFalse(Thread.currentThread().isInterrupted());
    }
}
