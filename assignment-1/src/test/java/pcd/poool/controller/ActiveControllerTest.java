package pcd.poool.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActiveControllerTest {

    private static class TestTarget {
        final AtomicInteger counter = new AtomicInteger(0);
    }

    @Test
    void testCommandExecution() throws InterruptedException {
        TestTarget target = new TestTarget();
        ActiveController<TestTarget> controller = new ActiveController<>(target, 10);
        controller.start();

        CountDownLatch latch = new CountDownLatch(1);
        controller.notifyNewCmd(t -> {
            t.counter.incrementAndGet();
            latch.countDown();
        });

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, target.counter.get());

        controller.shutdown();
        controller.join(2000);
    }

    @Test
    void testRobustnessToExceptions() throws InterruptedException {
        TestTarget target = new TestTarget();
        ActiveController<TestTarget> controller = new ActiveController<>(target, 10);
        controller.start();

        CountDownLatch firstLatch = new CountDownLatch(1);
        CountDownLatch secondLatch = new CountDownLatch(1);

        // Command that throws an exception
        controller.notifyNewCmd(t -> {
            firstLatch.countDown();
            throw new RuntimeException("Simulated exception in command");
        });

        // Subsequent command to verify the loop is still running
        controller.notifyNewCmd(t -> {
            t.counter.incrementAndGet();
            secondLatch.countDown();
        });

        assertTrue(firstLatch.await(2, TimeUnit.SECONDS));
        assertTrue(secondLatch.await(2, TimeUnit.SECONDS));
        assertEquals(1, target.counter.get());

        controller.shutdown();
        controller.join(2000);
    }
}
