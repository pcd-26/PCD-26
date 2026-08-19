package pcd.poool.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.physics.config.StandardGameBoardConf;
import pcd.poool.model.physics.sequential.SequentialPhysicsEngine;

class GameLoopTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @Test
    void oneTickExecutesCommandsAdvancesTheGameAndPublishesState() throws Exception {
        var loop = new GameLoop(
                new StandardGameBoardConf(),
                new SequentialPhysicsEngine(),
                GameModel.StartupCountdown.disabled());
        var shot = loop.shootHuman(new V2d(1, 0));

        loop.tick(5);

        assertTrue(await(shot, TIMEOUT));
        assertTrue(loop.snapshot().game().simulatedSteps() > 0);
    }

    @Test
    void closingTheLoopRejectsNewCommands() throws Exception {
        var loop = new GameLoop(
                new StandardGameBoardConf(),
                new SequentialPhysicsEngine(),
                GameModel.StartupCountdown.disabled());

        loop.close();

        assertFalse(await(loop.shootHuman(new V2d(1, 0)), TIMEOUT));
    }

    private static <T> T await(CompletableFuture<T> completion, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        return completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
}
