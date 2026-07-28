package pcd.poool.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameSnapshot;
import pcd.poool.model.game.GameStatus;

class BotAgentTest {

    @Test
    void submitsShotAfterThinkTimeWhenBotCanShoot() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            var running = new AtomicBoolean(true);
            var shots = new AtomicInteger();
            var agent = new BotAgent(
                    () -> readySnapshot(),
                    () -> {
                        shots.incrementAndGet();
                        running.set(false);
                    },
                    running::get,
                    0);

            agent.run();

            assertTrue(shots.get() >= 1);
        });
    }

    private static RuntimeGameSnapshot readySnapshot() {
        return new RuntimeGameSnapshot(
                new GameSnapshot(0, 0, GameStatus.RUNNING_STILL, null, null, false, true, 0, 0, 0),
                List.of(),
                null,
                null,
                List.of(),
                new V2d(1, 0));
    }
}
