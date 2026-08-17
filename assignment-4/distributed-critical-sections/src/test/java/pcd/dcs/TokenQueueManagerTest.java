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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenQueueManagerTest {

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
    @DisplayName("Precondition error inspection identifies 406 channel close")
    void identifiesPreconditionFailedException() {
        AMQP.Channel.Close closeReason = new AMQP.Channel.Close.Builder()
                .replyCode(406)
                .replyText("PRECONDITION_FAILED - inequivalent arg 'x-max-length'")
                .build();

        com.rabbitmq.client.ShutdownSignalException sse =
                new com.rabbitmq.client.ShutdownSignalException(false, false, closeReason, null);

        IOException ioex = new IOException(sse);

        assertTrue(TokenQueueManager.isPreconditionFailed(ioex),
                "isPreconditionFailed must detect 406 channel shutdown signal");
    }

    @Test
    @DisplayName("Queue declaration fails fast on inequivalent broker arguments without deleting the queue")
    void queueDeclarationFailsFastOnInequivalentArguments() throws Exception {
        String csName = "queue-mgr-" + UUID.randomUUID();
        TokenQueueManager manager = new TokenQueueManager(csName);

        // Pre-create queue with conflicting x-arguments
        try (Channel ch = connection.createChannel()) {
            Map<String, Object> args = new HashMap<>();
            args.put("x-max-length", 500);
            ch.queueDeclare(manager.criticalSectionTokenQueue(), true, false, false, args);
        }

        // TokenQueueManager should detect 406 PRECONDITION_FAILED and fail without deleting the queue.
        Channel activeChannel = connection.createChannel();
        try {
            assertThrows(IOException.class, () -> manager.declareQueue(activeChannel),
                    "Inequivalent broker arguments should fail fast");

            try (Channel inspectChannel = connection.createChannel()) {
                AMQP.Queue.DeclareOk state = inspectChannel.queueDeclarePassive(manager.criticalSectionTokenQueue());
                assertEquals(0, state.getMessageCount(), "Existing queue should remain intact after fail-fast");
            }
        } finally {
            if (activeChannel.isOpen()) {
                activeChannel.close();
            }
        }
    }
}
