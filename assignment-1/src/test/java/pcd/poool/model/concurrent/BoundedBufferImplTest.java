package pcd.poool.model.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedBufferImplTest {

    @Test
    void pollReturnsNullWhenBufferIsEmpty() {
        var buffer = new BoundedBufferImpl<String>(1);

        assertNull(buffer.poll());
    }

    @Test
    void preservesFifoOrdering() throws InterruptedException {
        var buffer = new BoundedBufferImpl<String>(2);

        buffer.put("first");
        buffer.put("second");

        assertEquals("first", buffer.get());
        assertEquals("second", buffer.get());
    }

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
