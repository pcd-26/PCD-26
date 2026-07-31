package pcd.shas.siren;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SirenActor.
 */
public class SirenActorTest {

    private static ActorTestKit testKit;

    @BeforeAll
    public static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    public static void teardown() {
        testKit.shutdownTestKit();
    }

    @Test
    public void testSirenTransitions() {
        ActorRef<SirenActor.Command> siren = testKit.spawn(SirenActor.create());
        TestProbe<SirenActor.StateSnapshot> probe = testKit.createTestProbe(SirenActor.StateSnapshot.class);

        // Initial state: inactive
        siren.tell(new SirenActor.QueryState(probe.getRef()));
        assertFalse(probe.receiveMessage().active());

        // Activate
        siren.tell(new SirenActor.Activate());
        siren.tell(new SirenActor.QueryState(probe.getRef()));
        assertTrue(probe.receiveMessage().active());

        // Deactivate
        siren.tell(new SirenActor.Deactivate());
        siren.tell(new SirenActor.QueryState(probe.getRef()));
        assertFalse(probe.receiveMessage().active());
    }
}
