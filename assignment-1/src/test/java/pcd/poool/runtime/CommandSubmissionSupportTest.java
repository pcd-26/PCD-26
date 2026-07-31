package pcd.poool.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CommandSubmissionSupportTest {

    @Test
    void completesReceiptWhenControllerExecutesCommand() throws InterruptedException {
        var queue = new CommandQueueMonitorSupport<GameCommand>();
        var receipt = CommandSubmissionSupport.submit(queue, game -> "done", "rejected");

        queue.poll().execute(null);

        assertEquals("done", receipt.await(Duration.ofMillis(100)));
    }

    @Test
    void completesRejectedResultWhenQueueIsClosed() throws InterruptedException {
        var queue = new CommandQueueMonitorSupport<GameCommand>();
        queue.close();

        var receipt = CommandSubmissionSupport.submit(queue, game -> "done", "rejected");

        assertEquals("rejected", receipt.await(Duration.ofMillis(100)));
    }

    @Test
    void propagatesOperationFailuresThroughReceipt() {
        var queue = new CommandQueueMonitorSupport<GameCommand>();
        var failure = new IllegalStateException("boom");
        var receipt = CommandSubmissionSupport.submit(queue, game -> {
            throw failure;
        }, "rejected");

        queue.poll().execute(null);

        assertEquals(failure, assertThrows(IllegalStateException.class,
                () -> receipt.await(Duration.ofMillis(100))));
    }
}
