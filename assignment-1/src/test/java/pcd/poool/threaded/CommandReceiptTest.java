package pcd.poool.threaded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CommandReceiptTest {

    @Test
    void awaitReturnsCompletedResult() throws InterruptedException {
        var receipt = new CommandReceipt<String>();

        receipt.complete("done");

        assertEquals("done", receipt.await(Duration.ofMillis(100)));
    }

    @Test
    void awaitTimesOutWhenCommandNeverCompletes() {
        var receipt = new CommandReceipt<String>();

        assertThrows(IllegalStateException.class, () -> receipt.await(Duration.ofMillis(20)));
    }

    @Test
    void awaitUnblocksWhenAnotherThreadCompletesReceipt() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var receipt = new CommandReceipt<Integer>();
            var completer = new Thread(() -> receipt.complete(7));

            completer.start();

            assertEquals(7, receipt.await(Duration.ofSeconds(1)));
            completer.join();
        });
    }
}
