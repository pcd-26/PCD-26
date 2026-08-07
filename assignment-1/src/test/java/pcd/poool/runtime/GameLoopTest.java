package pcd.poool.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.model.physics.sequential.SequentialPhysicsEngine;

class GameLoopTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test
    void oneTickExecutesCommandsAdvancesTheGameAndPublishesState() throws InterruptedException {
        var loop = new GameLoop(
                new StandardGameBoardConf(),
                new SequentialPhysicsEngine(),
                GameModel.StartupCountdown.disabled());
        var shot = loop.shootHuman(new V2d(1, 0));

        loop.tick(5);

        assertTrue(shot.await(TIMEOUT));
        assertTrue(loop.snapshot().game().simulatedSteps() > 0);
    }

    @Test
    void closingTheLoopRejectsNewCommands() throws InterruptedException {
        var loop = new GameLoop(
                new StandardGameBoardConf(),
                new SequentialPhysicsEngine(),
                GameModel.StartupCountdown.disabled());

        loop.close();

        assertFalse(loop.shootHuman(new V2d(1, 0)).await(TIMEOUT));
    }
}
