package pcd.shas.controlunit;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests verifying the state machine logic and recovery behavior of the ControlUnitActor.
 */
public class ControlUnitActorTest {

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
    public void testInitialStateIsRecovery() {
        ActorRef<ControlUnitActor.Command> cu = testKit.spawn(ControlUnitActor.create("1234"));
        TestProbe<ControlUnitActor.StateSnapshot> probe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);

        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        ControlUnitActor.StateSnapshot snapshot = probe.receiveMessage();
        assertEquals(AlarmState.RECOVERY, snapshot.state());
    }

    @Test
    public void testRecoveryDisarmsWithCorrectPin() {
        ActorRef<ControlUnitActor.Command> cu = testKit.spawn(ControlUnitActor.create("1234"));
        TestProbe<ControlUnitActor.StateSnapshot> probe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);

        // Submit incorrect PIN
        cu.tell(new ControlUnitActor.PinSubmitted("9999"));
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.RECOVERY, probe.receiveMessage().state());

        // Submit correct PIN
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.DISARMED, probe.receiveMessage().state());
    }

    @Test
    public void testSensorEventsIgnoredInRecovery() {
        ActorRef<ControlUnitActor.Command> cu = testKit.spawn(ControlUnitActor.create("1234"));
        TestProbe<ControlUnitActor.StateSnapshot> probe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);

        SensorInfo info = new SensorInfo("s1", SensorType.MOTION, Zone.PERIMETER);
        cu.tell(new ControlUnitActor.SensorActivated(info));

        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.RECOVERY, probe.receiveMessage().state());
    }
}
