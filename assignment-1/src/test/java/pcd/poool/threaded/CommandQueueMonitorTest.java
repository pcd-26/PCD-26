package pcd.poool.threaded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CommandQueueMonitorTest {

    @Test
    void pollReturnsCommandsInSubmissionOrder() {
        var queue = new CommandQueueMonitor();
        var first = new CountingCommand();
        var second = new CountingCommand();

        assertTrue(queue.put(first));
        assertTrue(queue.put(second));

        assertEquals(first, queue.poll());
        assertEquals(second, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    void closeRejectsPendingCommandsAndPreventsNewSubmissions() {
        var queue = new CommandQueueMonitor();
        var command = new CountingCommand();

        assertTrue(queue.put(command));
        queue.close();

        assertEquals(1, command.rejections.get());
        assertFalse(queue.put(new CountingCommand()));
        assertNull(queue.poll());
    }

    private static class CountingCommand implements GameCommand {

        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicInteger rejections = new AtomicInteger();

        @Override
        public void execute(pcd.poool.model.game.GameModel game) {
            executions.incrementAndGet();
        }

        @Override
        public void reject() {
            rejections.incrementAndGet();
        }
    }
}
