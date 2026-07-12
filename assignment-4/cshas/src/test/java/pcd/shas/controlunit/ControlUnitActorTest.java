package pcd.shas.controlunit;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.siren.SirenActor;

import java.time.Duration;
import java.util.Set;

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
        assertEquals(AlarmState.RECOVERY, probe.receiveMessage().state());
    }

    @Test
    public void testRecoveryIgnoresSensorEventsAndRequiresCorrectPin() {
        ActorRef<ControlUnitActor.Command> cu = testKit.spawn(ControlUnitActor.create("1234"));
        TestProbe<ControlUnitActor.StateSnapshot> probe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);

        cu.tell(new ControlUnitActor.SensorActivated(new SensorInfo("s1", SensorType.MOTION, Zone.PERIMETER)));
        cu.tell(new ControlUnitActor.PinSubmitted("9999"));
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.RECOVERY, probe.receiveMessage().state());

        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.DISARMED, probe.receiveMessage().state());
    }

    @Test
    public void testExitDelayArmsAfterTimeout() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = testKit.spawn(ControlUnitActor.create(
                "1234",
                Duration.ofMillis(50),
                Duration.ofMillis(50)
        ));
        TestProbe<ControlUnitActor.StateSnapshot> probe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);

        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.DISARMED, probe.receiveMessage().state());

        cu.tell(new ControlUnitActor.ArmAll());
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.EXIT_DELAY, probe.receiveMessage().state());

        Thread.sleep(120);
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.ARMED, probe.receiveMessage().state());
    }

    @Test
    public void testEntryDelayDisarmsWithPinAndAlarmsOnTimeout() throws Exception {
        TestProbe<SirenActor.Command> sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        testKit.system().receptionist().tell(
                Receptionist.register(SirenActor.SIREN_SERVICE_KEY, sirenProbe.getRef())
        );

        ActorRef<ControlUnitActor.Command> cu = testKit.spawn(ControlUnitActor.create(
                "1234",
                Duration.ofMillis(50),
                Duration.ofMillis(50)
        ));
        TestProbe<ControlUnitActor.StateSnapshot> probe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);

        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.ArmAll());
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        Thread.sleep(120);
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.ARMED, probe.receiveMessage().state());

        cu.tell(new ControlUnitActor.SensorActivated(new SensorInfo("door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.ENTRY_DELAY, probe.receiveMessage().state());

        Thread.sleep(120);
        sirenProbe.expectMessage(new SirenActor.Activate());
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.ALARM, probe.receiveMessage().state());

        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        sirenProbe.expectMessage(new SirenActor.Deactivate());
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.DISARMED, probe.receiveMessage().state());
    }

    @Test
    public void testStaleTimeoutMessagesDoNotChangeCurrentState() throws Exception {
        ActorRef<ControlUnitActor.Command> cu = testKit.spawn(ControlUnitActor.create(
                "1234",
                Duration.ofMillis(50),
                Duration.ofMillis(50)
        ));
        TestProbe<ControlUnitActor.StateSnapshot> probe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);

        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.ArmAll());
        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.EXIT_DELAY, probe.receiveMessage().state());

        cu.tell(new ControlUnitActor.PinSubmitted("1234"));
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.DISARMED, probe.receiveMessage().state());

        cu.tell(new ControlUnitActor.ExitDelayTimeout(2));
        cu.tell(new ControlUnitActor.EntryDelayTimeout(2));
        cu.tell(new ControlUnitActor.QueryState(probe.getRef()));
        assertEquals(AlarmState.DISARMED, probe.receiveMessage().state());
    }
}
