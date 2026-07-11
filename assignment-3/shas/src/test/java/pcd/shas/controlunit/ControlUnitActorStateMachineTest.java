package pcd.shas.controlunit;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.siren.SirenActor;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlUnitActorStateMachineTest {

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
    void initialStateIsDisarmed() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void correctPinInDisarmedEntersExitDelay() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(controlUnit, AlarmState.EXIT_DELAY);
        sirenProbe.expectNoMessage();
    }

    @Test
    void incorrectPinInDisarmedChangesNothing() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("0000"));

        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void sensorsAreIgnoredInDisarmed() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));

        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void sensorsAreIgnoredInExitDelay() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));

        assertState(controlUnit, AlarmState.EXIT_DELAY);
        sirenProbe.expectNoMessage();
    }

    @Test
    void exitTimeoutTransitionsToArmed() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());

        assertState(controlUnit, AlarmState.ARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void sensorEventInArmedEntersEntryDelay() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));

        assertState(controlUnit, AlarmState.ENTRY_DELAY);
        sirenProbe.expectNoMessage();
    }

    @Test
    void correctPinInArmedReturnsToDisarmed() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectMessage(new SirenActor.Deactivate());
    }

    @Test
    void correctPinDuringEntryDelayReturnsToDisarmed() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));
        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectMessage(new SirenActor.Deactivate());
    }

    @Test
    void entryTimeoutTransitionsToAlarm() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));
        controlUnit.tell(new ControlUnitActor.EntryDelayTimeout());

        assertState(controlUnit, AlarmState.ALARM);
        sirenProbe.expectMessage(new SirenActor.Activate());
    }

    @Test
    void sensorEventsDuringEntryDelayHaveNoEffect() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("back_door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));

        assertState(controlUnit, AlarmState.ENTRY_DELAY);
        sirenProbe.expectNoMessage();
    }

    @Test
    void correctPinDuringAlarmReturnsToDisarmed() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));
        controlUnit.tell(new ControlUnitActor.EntryDelayTimeout());
        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectMessage(new SirenActor.Activate());
        sirenProbe.expectMessage(new SirenActor.Deactivate());
    }

    @Test
    void incorrectPinDuringAlarmHasNoEffect() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));
        controlUnit.tell(new ControlUnitActor.EntryDelayTimeout());
        controlUnit.tell(new ControlUnitActor.PinSubmitted("0000"));

        assertState(controlUnit, AlarmState.ALARM);
        sirenProbe.expectMessage(new SirenActor.Activate());
        sirenProbe.expectNoMessage();
    }

    @Test
    void staleTimeoutMessagesCannotCauseInvalidTransitions() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        assertState(controlUnit, AlarmState.DISARMED);

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectMessage(new SirenActor.Deactivate());

        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        assertState(controlUnit, AlarmState.DISARMED);

        controlUnit.tell(new ControlUnitActor.EntryDelayTimeout());
        assertState(controlUnit, AlarmState.DISARMED);

        sirenProbe.expectNoMessage();
    }

    private void assertState(org.apache.pekko.actor.typed.ActorRef<ControlUnitActor.Command> controlUnit, AlarmState expected) {
        TestProbe<ControlUnitActor.StateSnapshot> stateProbe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(expected, stateProbe.receiveMessage().state());
    }
}
