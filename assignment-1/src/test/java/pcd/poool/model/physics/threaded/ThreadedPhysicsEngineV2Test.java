package pcd.poool.model.physics.threaded;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.Player;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.config.LargeBoardConf;
import pcd.poool.model.physics.sequential.PhysicsEngine;
import pcd.poool.model.physics.common.PhysicsDefaults;

class ThreadedPhysicsEngineV2Test {

    @Test
    void exposesConfiguredWorkerCount() {
        try (var engine = new ThreadedPhysicsEngineV2(3)) {
            assertEquals(3, engine.workerCount());
        }
    }

    @Test
    @Timeout(5)
    void matchesSequentialBaselineOnDenseCollisionScenario() {
        var conf = new LargeBoardConf();

        var sequentialBoard = new Board(new PhysicsEngine());
        sequentialBoard.init(conf);
        sequentialBoard.kick(Player.HUMAN, new V2d(0.95, 0.15));
        sequentialBoard.kick(Player.BOT, new V2d(-0.9, -0.1));

        try (var engine = new ThreadedPhysicsEngineV2(4)) {
            var threadedBoard = new Board(engine);
            threadedBoard.init(conf);
            threadedBoard.kick(Player.HUMAN, new V2d(0.95, 0.15));
            threadedBoard.kick(Player.BOT, new V2d(-0.9, -0.1));

            for (int i = 0; i < 25; i++) {
                sequentialBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
                threadedBoard.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }

            assertEquals(sequentialBoard.getBalls(), threadedBoard.getBalls());
            assertEquals(sequentialBoard.getPlayerBall(), threadedBoard.getPlayerBall());
            assertEquals(sequentialBoard.getBotBall(), threadedBoard.getBotBall());
            assertEquals(sequentialBoard.getPocketedSmallBalls(), threadedBoard.getPocketedSmallBalls());
            assertEquals(sequentialBoard.isPlayerBallPocketed(), threadedBoard.isPlayerBallPocketed());
            assertEquals(sequentialBoard.isBotBallPocketed(), threadedBoard.isBotBallPocketed());
        }
    }
}
