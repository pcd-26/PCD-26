package pcd.shas;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pcd.shas.siren.SirenActor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SirenActorTest {

    private ActorTestKit testKit;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create();
    }

    @AfterEach
    void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    void sirenStartsSilent() {
        var siren = testKit.spawn(SirenActor.create());
        assertState(siren, false);
    }

    @Test
    void activateAndDeactivateAreIdempotent() {
        var siren = testKit.spawn(SirenActor.create());

        siren.tell(new SirenActor.Activate());
        siren.tell(new SirenActor.Activate());
        assertState(siren, true);

        siren.tell(new SirenActor.Deactivate());
        siren.tell(new SirenActor.Deactivate());
        assertState(siren, false);
    }

    private void assertState(org.apache.pekko.actor.typed.ActorRef<SirenActor.Command> siren, boolean expected) {
        TestProbe<SirenActor.StateSnapshot> probe = testKit.createTestProbe(SirenActor.StateSnapshot.class);
        siren.tell(new SirenActor.QueryState(probe.getRef()));
        boolean active = probe.receiveMessage().active();
        if (expected) {
            assertTrue(active);
        } else {
            assertFalse(active);
        }
    }
}
