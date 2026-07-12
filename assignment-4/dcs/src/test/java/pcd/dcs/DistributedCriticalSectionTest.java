package pcd.dcs;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DistributedCriticalSectionTest {

    private Connection connection;
    private final String host = "localhost";
    private final int port = 5672;

    @BeforeAll
    public void setupAll() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(port);
        // Connect to RabbitMQ; if it is not running, this will fail immediately,
        // alerting us to check the service.
        try {
            connection = factory.newConnection();
        } catch (Exception e) {
            fail("Failed to connect to RabbitMQ on localhost:5672. Ensure RabbitMQ is running before starting tests. Error: " + e.getMessage());
        }
    }

    @AfterAll
    public void teardownAll() throws Exception {
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
    }

    @Test
    public void testAcquireAndRelease() throws Exception {
        String csName = "test-acquire-release-" + System.currentTimeMillis();
        try (DistributedCriticalSection dcs = new DistributedCriticalSection(connection, csName)) {
            dcs.enter();
            // Critical section body
            dcs.exit();
        }
    }

    @Test
    public void testConcurrentBootstrapCreatesExactlyOneToken() throws Exception {
        String csName = "test-bootstrap-" + System.currentTimeMillis();
        int threadCount = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    try (DistributedCriticalSection ignored = new DistributedCriticalSection(host, port, csName)) {
                        // Constructor bootstrap only; close immediately.
                    }
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(readyLatch.await(5, TimeUnit.SECONDS), "Workers did not become ready in time");
        startLatch.countDown();
        assertTrue(doneLatch.await(20, TimeUnit.SECONDS), "Bootstrap workers did not finish in time");
        executor.shutdownNow();

        assertTrue(failures.isEmpty(), "Bootstrap failed in at least one worker: " + failures);
        QueueStatus status = readQueueStatus(tokenQueueName(csName));
        assertEquals(1, status.messageCount(), "Concurrent bootstrap must leave exactly one token message");
        assertEquals(0, status.consumerCount(), "No consumer should remain registered after bootstrap-only construction");
    }

    @Test
    public void testReentryFails() throws Exception {
        String csName = "test-reentry-" + System.currentTimeMillis();
        try (DistributedCriticalSection dcs = new DistributedCriticalSection(connection, csName)) {
            dcs.enter();
            assertThrows(IllegalStateException.class, dcs::enter, "Reentering an already acquired lock should fail.");
            dcs.exit();
        }
    }

    @Test
    public void testMutualExclusion() throws Exception {
        String csName = "test-mutex-" + System.currentTimeMillis();
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger activeProcessesInCS = new AtomicInteger(0);
        List<Boolean> safetyViolations = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try (DistributedCriticalSection dcs = new DistributedCriticalSection(connection, csName)) {
                    dcs.enter();
                    int active = activeProcessesInCS.incrementAndGet();
                    if (active > 1) {
                        safetyViolations.add(true);
                    }

                    // Simulate some work in the critical section
                    Thread.sleep(100);

                    activeProcessesInCS.decrementAndGet();
                    dcs.exit();
                } catch (Exception e) {
                    e.printStackTrace();
                    safetyViolations.add(true);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads did not complete execution in time");
        executor.shutdown();

        assertTrue(safetyViolations.isEmpty(), "Safety violation detected: multiple threads entered the critical section simultaneously.");
    }

    @Test
    public void testCrashRecovery() throws Exception {
        String csName = "test-crash-" + System.currentTimeMillis();

        // 1. First process acquires the lock
        DistributedCriticalSection dcs1 = new DistributedCriticalSection(host, port, csName);
        dcs1.enter();
        QueueStatus heldStatus = readQueueStatus(tokenQueueName(csName));
        assertEquals(0, heldStatus.messageCount(), "The token should be in-flight while the critical section is held");
        assertEquals(1, heldStatus.consumerCount(), "The holder keeps one consumer registered while in the critical section");

        // 2. Second process tries to acquire the lock in a background thread and blocks
        CompletableFuture<Boolean> dcs2Acquired = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try (DistributedCriticalSection dcs2 = new DistributedCriticalSection(host, port, csName)) {
                dcs2.enter();
                dcs2Acquired.complete(true);
                dcs2.exit();
            } catch (Exception e) {
                dcs2Acquired.completeExceptionally(e);
            }
        });
        t.start();

        // Give thread B time to start waiting
        Thread.sleep(500);
        assertFalse(dcs2Acquired.isDone(), "Second process should be waiting for the lock.");

        // 3. Close the first process's instance (simulating a crash where connection/channel is closed)
        dcs1.close();

        // 4. Verify that the second process is now able to acquire the lock automatically
        assertTrue(dcs2Acquired.get(5, TimeUnit.SECONDS), "Second process should have acquired the lock after the first crashed.");
    }

    private String tokenQueueName(String csName) {
        return "cs_token_" + csName;
    }

    private QueueStatus readQueueStatus(String queueName) throws Exception {
        try (Channel statusChannel = connection.createChannel()) {
            AMQP.Queue.DeclareOk declareOk = statusChannel.queueDeclarePassive(queueName);
            return new QueueStatus(declareOk.getMessageCount(), declareOk.getConsumerCount());
        }
    }

    private record QueueStatus(int messageCount, int consumerCount) {
    }
}
