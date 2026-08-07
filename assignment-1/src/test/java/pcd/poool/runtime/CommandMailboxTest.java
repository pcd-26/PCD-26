package pcd.poool.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class CommandMailboxTest {

    private static final Duration TIMEOUT = Duration.ofMillis(100);

    @Test
    void drainsCommandsInSubmissionOrderAndCompletesReceipts() throws InterruptedException {
        var mailbox = new CommandMailbox();
        var order = new ArrayList<Integer>();
        var first = mailbox.submit(game -> {
            order.add(1);
            return "first";
        }, "rejected");
        var second = mailbox.submit(game -> {
            order.add(2);
            return "second";
        }, "rejected");

        mailbox.drain(null);

        assertEquals("first", first.await(TIMEOUT));
        assertEquals("second", second.await(TIMEOUT));
        assertEquals(java.util.List.of(1, 2), order);
    }

    @Test
    void closeRejectsPendingAndFutureCommands() throws InterruptedException {
        var mailbox = new CommandMailbox();
        var pending = mailbox.submit(game -> true, false);

        mailbox.close();

        assertEquals(false, pending.await(TIMEOUT));
        assertEquals(false, mailbox.submit(game -> true, false).await(TIMEOUT));
    }

    @Test
    void operationFailureIsReportedThroughTheReceipt() {
        var mailbox = new CommandMailbox();
        var failure = new IllegalStateException("boom");
        var receipt = mailbox.submit(game -> {
            throw failure;
        }, "rejected");

        mailbox.drain(null);

        assertEquals(failure, assertThrows(IllegalStateException.class, () -> receipt.await(TIMEOUT)));
    }

    @Test
    void awaitTimesOutWhenTheControllerHasNotDrainedTheCommand() {
        var mailbox = new CommandMailbox();
        var receipt = mailbox.submit(game -> true, false);

        assertThrows(IllegalStateException.class, () -> receipt.await(Duration.ofMillis(10)));
    }
}
