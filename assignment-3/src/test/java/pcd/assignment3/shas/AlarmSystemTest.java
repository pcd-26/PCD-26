package pcd.assignment3.shas;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pcd.assignment3.shas.common.SensorInfo;
import pcd.assignment3.shas.common.SensorType;
import pcd.assignment3.shas.controlunit.ControlUnitActor;
import pcd.assignment3.shas.keypad.KeypadActor;
import pcd.assignment3.shas.siren.SirenActor;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and integration tests for the smart home alarm system, verifying all state transitions,
 * timing constraints, and partial arming features.
 */
public class AlarmSystemTest {

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
    public void testInitialStateDisarmed() {
        TestProbe<SirenActor.Command> sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        ActorRef<ControlUnitActor.Command> controlUnit = testKit.spawn(
                ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef())
        );

        TestProbe<ControlUnitActor.StateReport> stateProbe = testKit.createTestProbe(ControlUnitActor.StateReport.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));

        ControlUnitActor.StateReport report = stateProbe.receiveMessage();
        assertEquals(ControlUnitActor.AlarmState.DISARMED, report.state());
        assertFalse(report.fullyArmed());
    }

    @Test
    public void testIncorrectPinRejected() {
        TestProbe<SirenActor.Command> sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        ActorRef<ControlUnitActor.Command> controlUnit = testKit.spawn(
                ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef())
        );

        TestProbe<KeypadActor.Command> keypadProbe = testKit.createTestProbe(KeypadActor.Command.class);
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered("9999", Collections.emptySet(), keypadProbe.getRef()));

        keypadProbe.expectMessage(new KeypadActor.PinRejected());

        TestProbe<ControlUnitActor.StateReport> stateProbe = testKit.createTestProbe(ControlUnitActor.StateReport.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(ControlUnitActor.AlarmState.DISARMED, stateProbe.receiveMessage().state());
    }

    @Test
    public void testArmingFullAndArmedTransition() {
        TestProbe<SirenActor.Command> sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        // Short exit delay for testing speed
        ActorRef<ControlUnitActor.Command> controlUnit = testKit.spawn(
                ControlUnitActor.create("1234", Duration.ofMillis(300), Duration.ofMillis(300), sirenProbe.getRef())
        );

        TestProbe<KeypadActor.Command> keypadProbe = testKit.createTestProbe(KeypadActor.Command.class);
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered("1234", Collections.emptySet(), keypadProbe.getRef()));

        keypadProbe.expectMessage(new KeypadActor.PinAccepted());

        TestProbe<ControlUnitActor.StateReport> stateProbe = testKit.createTestProbe(ControlUnitActor.StateReport.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        ControlUnitActor.StateReport report1 = stateProbe.receiveMessage();
        assertEquals(ControlUnitActor.AlarmState.EXIT_DELAY, report1.state());
        assertTrue(report1.fullyArmed());

        // Wait for exit delay to elapse (300ms + margin)
        stateProbe.expectNoMessage(Duration.ofMillis(400));

        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        ControlUnitActor.StateReport report2 = stateProbe.receiveMessage();
        assertEquals(ControlUnitActor.AlarmState.ARMED, report2.state());
        assertTrue(report2.fullyArmed());
    }

    @Test
    public void testDisarmDuringExitDelay() {
        TestProbe<SirenActor.Command> sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        ActorRef<ControlUnitActor.Command> controlUnit = testKit.spawn(
                ControlUnitActor.create("1234", Duration.ofSeconds(5), Duration.ofSeconds(5), sirenProbe.getRef())
        );

        TestProbe<KeypadActor.Command> keypadProbe = testKit.createTestProbe(KeypadActor.Command.class);
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered("1234", Collections.emptySet(), keypadProbe.getRef()));
        keypadProbe.expectMessage(new KeypadActor.PinAccepted());

        TestProbe<ControlUnitActor.StateReport> stateProbe = testKit.createTestProbe(ControlUnitActor.StateReport.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(ControlUnitActor.AlarmState.EXIT_DELAY, stateProbe.receiveMessage().state());

        // Disarm system before timeout
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered("1234", Collections.emptySet(), keypadProbe.getRef()));
        keypadProbe.expectMessage(new KeypadActor.PinAccepted());

        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(ControlUnitActor.AlarmState.DISARMED, stateProbe.receiveMessage().state());
    }

    @Test
    public void testIntrusionTriggersAlarm() {
        TestProbe<SirenActor.Command> sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        ActorRef<ControlUnitActor.Command> controlUnit = testKit.spawn(
                ControlUnitActor.create("1234", Duration.ofMillis(100), Duration.ofMillis(200), sirenProbe.getRef())
        );

        TestProbe<KeypadActor.Command> keypadProbe = testKit.createTestProbe(KeypadActor.Command.class);
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered("1234", Collections.emptySet(), keypadProbe.getRef()));
        keypadProbe.expectMessage(new KeypadActor.PinAccepted());

        // Wait for it to be armed
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        // Trigger sensor
        SensorInfo sensor = new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter");
        controlUnit.tell(new ControlUnitActor.SensorTriggered(sensor, java.time.Instant.now()));

        TestProbe<ControlUnitActor.StateReport> stateProbe = testKit.createTestProbe(ControlUnitActor.StateReport.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(ControlUnitActor.AlarmState.ENTRY_DELAY, stateProbe.receiveMessage().state());

        // Expect siren to activate after entry delay (200ms + margin)
        sirenProbe.expectMessage(Duration.ofMillis(400), new SirenActor.Activate());

        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(ControlUnitActor.AlarmState.ALARM, stateProbe.receiveMessage().state());
    }

    @Test
    public void testDisarmDuringEntryDelay() {
        TestProbe<SirenActor.Command> sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        ActorRef<ControlUnitActor.Command> controlUnit = testKit.spawn(
                ControlUnitActor.create("1234", Duration.ofMillis(50), Duration.ofSeconds(5), sirenProbe.getRef())
        );

        TestProbe<KeypadActor.Command> keypadProbe = testKit.createTestProbe(KeypadActor.Command.class);
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered("1234", Collections.emptySet(), keypadProbe.getRef()));
        keypadProbe.expectMessage(new KeypadActor.PinAccepted());

        // Wait to be armed
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        // Trigger sensor
        SensorInfo sensor = new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter");
        controlUnit.tell(new ControlUnitActor.SensorTriggered(sensor, java.time.Instant.now()));

        // Disarm system
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered("1234", Collections.emptySet(), keypadProbe.getRef()));
        keypadProbe.expectMessage(new KeypadActor.PinAccepted());

        // Verify it returned to Disarmed and siren was not triggered
        TestProbe<ControlUnitActor.StateReport> stateProbe = testKit.createTestProbe(ControlUnitActor.StateReport.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(ControlUnitActor.AlarmState.DISARMED, stateProbe.receiveMessage().state());
        sirenProbe.expectNoMessage(Duration.ofMillis(200));
    }

    @Test
    public void testStopAlarm() {
        TestProbe<SirenActor.Command> sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        ActorRef<ControlUnitActor.Command> controlUnit = testKit.spawn(
                ControlUnitActor.create("1234", Duration.ofMillis(50), Duration.ofMillis(50), sirenProbe.getRef())
        );

        TestProbe<KeypadActor.Command> keypadProbe = testKit.createTestProbe(KeypadActor.Command.class);
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered("1234", Collections.emptySet(), keypadProbe.getRef()));

        // Wait to arm, then trigger sensor
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        SensorInfo sensor = new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter");
        controlUnit.tell(new ControlUnitActor.SensorTriggered(sensor, java.time.Instant.now()));

        // Wait for alarm
        sirenProbe.expectMessage(new SirenActor.Activate());

        // Stop the alarm
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered("1234", Collections.emptySet(), keypadProbe.getRef()));
        keypadProbe.expectMessage(new KeypadActor.PinAccepted());
        sirenProbe.expectMessage(new SirenActor.Deactivate());

        TestProbe<ControlUnitActor.StateReport> stateProbe = testKit.createTestProbe(ControlUnitActor.StateReport.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(ControlUnitActor.AlarmState.DISARMED, stateProbe.receiveMessage().state());
    }

    @Test
    public void testPartialArmingAndZoneIsolation() {
        TestProbe<SirenActor.Command> sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        ActorRef<ControlUnitActor.Command> controlUnit = testKit.spawn(
                ControlUnitActor.create("1234", Duration.ofMillis(50), Duration.ofSeconds(5), sirenProbe.getRef())
        );

        TestProbe<KeypadActor.Command> keypadProbe = testKit.createTestProbe(KeypadActor.Command.class);
        
        // Arm only the Perimeter zone
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered("1234", Set.of("Perimeter"), keypadProbe.getRef()));
        keypadProbe.expectMessage(new KeypadActor.PinAccepted());

        // Wait to arm
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        TestProbe<ControlUnitActor.StateReport> stateProbe = testKit.createTestProbe(ControlUnitActor.StateReport.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        ControlUnitActor.StateReport report = stateProbe.receiveMessage();
        assertEquals(ControlUnitActor.AlarmState.ARMED, report.state());
        assertFalse(report.fullyArmed());
        assertTrue(report.activeZones().contains("Perimeter"));
        assertFalse(report.activeZones().contains("Living Area"));

        // Trigger sensor in INACTIVE zone "Living Area"
        SensorInfo inactiveSensor = new SensorInfo("living_room_motion", SensorType.MOTION, "Living Area");
        controlUnit.tell(new ControlUnitActor.SensorTriggered(inactiveSensor, java.time.Instant.now()));

        // Should still be in ARMED state
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(ControlUnitActor.AlarmState.ARMED, stateProbe.receiveMessage().state());

        // Trigger sensor in ACTIVE zone "Perimeter"
        SensorInfo activeSensor = new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter");
        controlUnit.tell(new ControlUnitActor.SensorTriggered(activeSensor, java.time.Instant.now()));

        // Should transition to ENTRY_DELAY
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(ControlUnitActor.AlarmState.ENTRY_DELAY, stateProbe.receiveMessage().state());
    }
}
