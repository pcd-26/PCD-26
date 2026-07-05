package pcd.poool.model.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedBufferImplTest {

    /**
     * Verifies that calling poll() on an empty bounded buffer immediately returns null
     * instead of blocking the calling thread.
     */
    @Test
    void pollReturnsNullWhenBufferIsEmpty() {
        var buffer = new BoundedBufferImpl<String>(1);

        assertNull(buffer.poll());
    }

    /**
     * Verifies that the bounded buffer maintains First-In, First-Out (FIFO) ordering
     * of elements when putting and getting items.
     */
    @Test
    void preservesFifoOrdering() throws InterruptedException {
        var buffer = new BoundedBufferImpl<String>(2);

        buffer.put("first");
        buffer.put("second");

        assertEquals("first", buffer.get());
        assertEquals("second", buffer.get());
    }

    /**
     * Verifies that the get() method blocks correctly when the buffer is empty,
     * and resumes successfully once a producer thread inserts an item.
     */
    @Test
    void getBlocksUntilProducerPutsAnItem() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var buffer = new BoundedBufferImpl<String>(1);
            var received = new AtomicReference<String>();

            var consumer = new Thread(() -> {
                try {
                    received.set(buffer.get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            consumer.start();

            Thread.sleep(50);
            buffer.put("released");
            consumer.join();

            assertEquals("released", received.get());
        });
    }
}
