package pcd.poool.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class CommandMailboxTest {

    private static final Duration TIMEOUT = Duration.ofMillis(100);

    @Test
    void drainsCommandsInSubmissionOrderAndCompletesReceipts() throws Exception {
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

        assertEquals("first", await(first, TIMEOUT));
        assertEquals("second", await(second, TIMEOUT));
        assertEquals(java.util.List.of(1, 2), order);
    }

    @Test
    void closeRejectsPendingAndFutureCommands() throws Exception {
        var mailbox = new CommandMailbox();
        var pending = mailbox.submit(game -> true, false);

        mailbox.close();

        assertEquals(false, await(pending, TIMEOUT));
        assertEquals(false, await(mailbox.submit(game -> true, false), TIMEOUT));
    }

    @Test
    void operationFailureIsReportedThroughTheReceipt() {
        var mailbox = new CommandMailbox();
        var failure = new IllegalStateException("boom");
        var receipt = mailbox.submit(game -> {
            throw failure;
        }, "rejected");

        mailbox.drain(null);

        var thrown = assertThrows(ExecutionException.class, () -> await(receipt, TIMEOUT));
        assertEquals(failure, thrown.getCause());
    }

    @Test
    void awaitTimesOutWhenTheControllerHasNotDrainedTheCommand() {
        var mailbox = new CommandMailbox();
        var receipt = mailbox.submit(game -> true, false);

        assertThrows(TimeoutException.class, () -> await(receipt, Duration.ofMillis(10)));
    }

    private static <T> T await(CompletableFuture<T> completion, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        return completion.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
