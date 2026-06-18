package pcd.poool.model.physics.threaded;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class WorkerCompletionMonitorTest {

    @Test
    @Timeout(3)
    void awaitPreservesInterruptAndStillWaitsForWorkersToFinish() throws InterruptedException {
        var monitor = new WorkerCompletionMonitor(1);
        var enteredAwait = new CountDownLatch(1);
        var interruptedAfterReturn = new AtomicBoolean(false);
        var completedNormally = new AtomicBoolean(false);

        var waiter = new Thread(() -> {
            enteredAwait.countDown();
            monitor.await();
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
            completedNormally.set(true);
        });
        waiter.start();

        assertTrue(enteredAwait.await(1, java.util.concurrent.TimeUnit.SECONDS));
        waiter.interrupt();
        Thread.sleep(50);
        monitor.completeOne();
        waiter.join(1_000);

        assertFalse(waiter.isAlive());
        assertTrue(completedNormally.get());
        assertTrue(interruptedAfterReturn.get());
    }
}
