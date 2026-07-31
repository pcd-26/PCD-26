package pcd.poool.threaded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import pcd.poool.runtime.CommandReceiptSupport;

class CommandReceiptTest {

    /**
     * Verifies that calling await on a receipt that has already completed successfully
     * immediately returns the correct result.
     */
    @Test
    void awaitReturnsCompletedResult() throws InterruptedException {
        var receipt = new CommandReceiptSupport<String>();

        receipt.complete("done");

        assertEquals("done", receipt.await(Duration.ofMillis(100)));
    }

    /**
     * Verifies that calling await on a receipt that never completes results in an
     * IllegalStateException after the timeout period.
     */
    @Test
    void awaitTimesOutWhenCommandNeverCompletes() {
        var receipt = new CommandReceiptSupport<String>();

        assertThrows(IllegalStateException.class, () -> receipt.await(Duration.ofMillis(20)));
    }

    /**
     * Verifies that if a thread is waiting on await, it is correctly unblocked and receives the
     * result as soon as another thread completes the receipt.
     */
    @Test
    void awaitUnblocksWhenAnotherThreadCompletesReceipt() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var receipt = new CommandReceiptSupport<Integer>();
            var completer = new Thread(() -> receipt.complete(7));

            completer.start();

            assertEquals(7, receipt.await(Duration.ofSeconds(1)));
            completer.join();
        });
    }
}
