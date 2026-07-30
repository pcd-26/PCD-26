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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrokerBootstrapLockTest {

    private static final String HOST = "localhost";
    private static final int PORT = 5672;

    private Connection connection;

    @BeforeAll
    void setupAll() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(HOST);
        factory.setPort(PORT);
        try {
            connection = factory.newConnection();
        } catch (Exception e) {
            fail("Failed to connect to RabbitMQ on localhost:5672: " + e.getMessage());
        }
    }

    @AfterAll
    void teardownAll() throws Exception {
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
    }

    @Test
    @DisplayName("Action executes while holding bootstrap lock and lock is deleted afterwards")
    void actionExecutesUnderLockAndDeletesLockQueue() throws Exception {
        String csName = "lock-test-" + UUID.randomUUID();
        BrokerBootstrapLock lock = new BrokerBootstrapLock(csName);
        AtomicBoolean executed = new AtomicBoolean(false);

        lock.withLock(connection, lockChannel -> {
            executed.set(true);
            AMQP.Queue.DeclareOk declareOk = lockChannel.queueDeclarePassive("cs_bootstrap_lock_" + csName);
            assertEquals("cs_bootstrap_lock_" + csName, declareOk.getQueue(), "Lock queue name must match");
        });

        assertTrue(executed.get(), "Action should have executed under lock");

        // Verify the lock queue was auto-deleted after withLock completed
        Channel inspectChannel = connection.createChannel();
        try {
            inspectChannel.queueDeclarePassive("cs_bootstrap_lock_" + csName);
            fail("Lock queue should have been deleted");
        } catch (IOException e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            assertTrue(msg != null && (msg.contains("NOT_FOUND") || msg.contains("404")),
                    "Passive check on deleted lock queue must fail with 404 NOT_FOUND");
        } finally {
            if (inspectChannel.isOpen()) {
                inspectChannel.close();
            }
        }
    }

    @Test
    @DisplayName("Lock acquisition times out if lock queue is held by another connection")
    void lockTimesOutWhenAlreadyHeld() throws Exception {
        String csName = "lock-timeout-" + UUID.randomUUID();
        String lockQueueName = "cs_bootstrap_lock_" + csName;

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(HOST);
        factory.setPort(PORT);

        try (Connection secondConnection = factory.newConnection();
             Channel holderChannel = secondConnection.createChannel()) {

            // Declare exclusive lock queue on second connection
            holderChannel.queueDeclare(lockQueueName, false, true, false, null);

            BrokerBootstrapLock lock = new BrokerBootstrapLock(csName, 300L, 50L);
            assertThrows(IOException.class, () -> lock.withLock(connection, lockChannel -> { }),
                    "Acquiring an already-held lock from a different connection should time out");
        }
    }
}
