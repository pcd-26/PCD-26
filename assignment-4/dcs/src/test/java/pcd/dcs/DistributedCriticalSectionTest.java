package pcd.dcs;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DistributedCriticalSectionTest {

    private static final String HOST = "localhost";
    private static final int PORT = 5672;

    private Connection connection;

    @BeforeAll
    void setupAll() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(HOST);
        factory.setPort(PORT);
        try {
            connection = factory.newConnection();
        } catch (Exception e) {
            fail("Failed to connect to RabbitMQ on localhost:5672. Ensure RabbitMQ is running before starting tests. Error: " + e.getMessage());
        }
    }

    @AfterAll
    void teardownAll() throws Exception {
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
    }

    @Test
    @DisplayName("Concurrent initialization creates one token")
    void concurrentInitializationCreatesExactlyOneToken() throws Exception {
        String csName = uniqueName("bootstrap");
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
                    try (DistributedCriticalSection ignored = new DistributedCriticalSection(HOST, PORT, csName)) {
                        // Constructor bootstrap only.
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
    @DisplayName("Interrupted bootstrap is recoverable")
    void interruptedBootstrapCanBeRecoveredByLaterProcess() throws Exception {
        String csName = uniqueName("bootstrap-interrupted");
        AtomicBoolean failOnFirstPublish = new AtomicBoolean(true);

        Connection failingConnection = newConnection();
        try {
            IOException failure = assertThrows(IOException.class, () ->
                    new DistributedCriticalSection(
                            failingConnection,
                            csName,
                            () -> {
                                if (failOnFirstPublish.getAndSet(false)) {
                                    throw new IOException("Injected bootstrap failure");
                                }
                            }));
            assertNotNull(failure);
        } finally {
            if (failingConnection.isOpen()) {
                failingConnection.close();
            }
        }

        QueueStatus afterFailure = readQueueStatus(tokenQueueName(csName));
        assertEquals(0, afterFailure.messageCount(), "No token should exist after a failed bootstrap publish");
        assertEquals(0, afterFailure.consumerCount(), "Bootstrap lock must be released after a failed bootstrap");

        try (DistributedCriticalSection recovered = new DistributedCriticalSection(HOST, PORT, csName)) {
            recovered.enter();
            recovered.exit();
        }

        QueueStatus afterRecovery = readQueueStatus(tokenQueueName(csName));
        assertEquals(1, afterRecovery.messageCount(), "A later process must be able to seed the missing token");
        assertEquals(0, afterRecovery.consumerCount(), "The token queue must be idle after recovery");
    }

    @Test
    @DisplayName("Release without ownership is rejected")
    void releaseWithoutOwnershipIsRejected() throws Exception {
        String csName = uniqueName("release-without-ownership");
        try (DistributedCriticalSection dcs = new DistributedCriticalSection(HOST, PORT, csName)) {
            assertThrows(IllegalStateException.class, dcs::exit);
        }
    }

    @Test
    @DisplayName("Double release is rejected")
    void doubleReleaseIsRejected() throws Exception {
        String csName = uniqueName("double-release");
        try (DistributedCriticalSection dcs = new DistributedCriticalSection(HOST, PORT, csName)) {
            dcs.enter();
            dcs.exit();
            assertThrows(IllegalStateException.class, dcs::exit);
        }
    }

    @Test
    @DisplayName("Acquire and release can repeat")
    void repeatedAcquireAndReleaseCyclesWork() throws Exception {
        String csName = uniqueName("cycles");
        try (DistributedCriticalSection dcs = new DistributedCriticalSection(HOST, PORT, csName)) {
            for (int i = 0; i < 4; i++) {
                dcs.enter();
                QueueStatus duringHold = readQueueStatus(tokenQueueName(csName));
                assertEquals(0, duringHold.messageCount(), "Token should be in-flight while held");
                assertEquals(1, duringHold.consumerCount(), "One consumer should be registered while held");

                dcs.exit();
                QueueStatus afterRelease = readQueueStatus(tokenQueueName(csName));
                assertEquals(1, afterRelease.messageCount(), "Token should be back in the queue after release");
                assertEquals(0, afterRelease.consumerCount(), "No consumer should remain after release");
            }
        }
    }

    @Test
    @DisplayName("Mutual exclusion holds for multiple concurrent processes")
    void mutualExclusionWithConcurrentProcesses() throws Exception {
        String csName = uniqueName("mutex");
        int threadCount = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger activeProcessesInCS = new AtomicInteger(0);
        List<Boolean> safetyViolations = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try (DistributedCriticalSection dcs = new DistributedCriticalSection(HOST, PORT, csName)) {
                    dcs.enter();
                    int active = activeProcessesInCS.incrementAndGet();
                    if (active > 1) {
                        safetyViolations.add(true);
                    }
                    Thread.sleep(100);
                    activeProcessesInCS.decrementAndGet();
                    dcs.exit();
                } catch (Exception e) {
                    safetyViolations.add(true);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(20, TimeUnit.SECONDS), "Threads did not complete execution in time");
        executor.shutdownNow();
        assertTrue(safetyViolations.isEmpty(), "Multiple processes entered the critical section simultaneously");
    }

    @Test
    @DisplayName("Independent critical sections do not interfere")
    void independentCriticalSectionNamesDoNotInterfere() throws Exception {
        String csNameA = uniqueName("independent-a");
        String csNameB = uniqueName("independent-b");

        try (DistributedCriticalSection dcsA = new DistributedCriticalSection(HOST, PORT, csNameA)) {
            dcsA.enter();

            CompletableFuture<Void> bCompleted = CompletableFuture.runAsync(() -> {
                try (DistributedCriticalSection dcsB = new DistributedCriticalSection(HOST, PORT, csNameB)) {
                    dcsB.enter();
                    dcsB.exit();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            bCompleted.get(5, TimeUnit.SECONDS);
            dcsA.exit();
        }

        assertEquals(1, readQueueStatus(tokenQueueName(csNameA)).messageCount(), "Queue A must keep its token");
        assertEquals(1, readQueueStatus(tokenQueueName(csNameB)).messageCount(), "Queue B must keep its token");
    }

    @Test
    @DisplayName("Crash while holding the token requeues it")
    void crashWhileHoldingTokenRequeuesUnacknowledgedToken() throws Exception {
        String csName = uniqueName("crash-hold");
        DistributedCriticalSection dcs1 = new DistributedCriticalSection(HOST, PORT, csName);
        try {
            dcs1.enter();

            QueueStatus heldStatus = readQueueStatus(tokenQueueName(csName));
            assertEquals(0, heldStatus.messageCount(), "The token should be in-flight while held");
            assertEquals(1, heldStatus.consumerCount(), "The holder should keep one consumer registered");
        } finally {
            dcs1.close();
        }

        QueueStatus afterClose = readQueueStatus(tokenQueueName(csName));
        assertEquals(1, afterClose.messageCount(), "RabbitMQ must requeue the unacknowledged token");
        assertEquals(0, afterClose.consumerCount(), "No consumer should remain after shutdown");

        try (DistributedCriticalSection dcs2 = new DistributedCriticalSection(HOST, PORT, csName)) {
            dcs2.enter();
            dcs2.exit();
        }
    }

    @Test
    @DisplayName("Shutdown on owner connection keeps the token available")
    void shutdownCleansUpWithoutLosingToken() throws Exception {
        String csName = uniqueName("shutdown");
        DistributedCriticalSection dcs = new DistributedCriticalSection(HOST, PORT, csName);
        try {
            dcs.enter();
        } finally {
            dcs.close();
        }

        QueueStatus afterShutdown = readQueueStatus(tokenQueueName(csName));
        assertEquals(1, afterShutdown.messageCount(), "Shutdown must not lose the token");
        assertEquals(0, afterShutdown.consumerCount(), "Shutdown must clear broker consumers");

        try (DistributedCriticalSection recovered = new DistributedCriticalSection(HOST, PORT, csName)) {
            recovered.enter();
            recovered.exit();
        }
    }

    private String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String tokenQueueName(String csName) {
        return "cs_token_" + csName;
    }

    private Connection newConnection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(HOST);
        factory.setPort(PORT);
        return factory.newConnection();
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
