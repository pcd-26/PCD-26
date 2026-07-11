package pcd.shas;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.ManualTime;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.siren.SirenActor;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlUnitActorTest {

    private ActorTestKit testKit;
    private ManualTime manualTime;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create("control-unit-tests", ManualTime.config());
        manualTime = ManualTime.get(testKit.system());
    }

    @AfterEach
    void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    void initialStateIsDisarmed() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(5), Duration.ofSeconds(5), sirenProbe.getRef()));

        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void correctPinMovesSystemToExitDelayAndExitTimeoutArmsSystem() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(controlUnit, AlarmState.EXIT_DELAY);

        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.ARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void incorrectPinHasNoEffectWhileDisarmed() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("0000"));

        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void leavingExitDelayCancelsObsoleteTimeout() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(controlUnit, AlarmState.EXIT_DELAY);

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectMessage(new SirenActor.Deactivate());

        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void armedSensorMovesSystemToEntryDelayAndEntryTimeoutAlarmsSystem() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.ARMED);

        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter")));
        assertState(controlUnit, AlarmState.ENTRY_DELAY);

        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.ALARM);
        sirenProbe.expectMessage(new SirenActor.Activate());
    }

    @Test
    void entryDelayPinDisarmsBeforeTimeout() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        manualTime.timePasses(Duration.ofSeconds(1));
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter")));
        assertState(controlUnit, AlarmState.ENTRY_DELAY);

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectMessage(new SirenActor.Deactivate());

        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void incorrectPinHasNoEffectInAlarm() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        manualTime.timePasses(Duration.ofSeconds(1));
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter")));
        manualTime.timePasses(Duration.ofSeconds(1));

        assertState(controlUnit, AlarmState.ALARM);
        sirenProbe.expectMessage(new SirenActor.Activate());

        controlUnit.tell(new ControlUnitActor.PinSubmitted("0000"));

        assertState(controlUnit, AlarmState.ALARM);
        sirenProbe.expectNoMessage();
    }

    @Test
    void correctPinInAlarmDisarmsAndTurnsSirenOff() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        manualTime.timePasses(Duration.ofSeconds(1));
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter")));
        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.ALARM);
        sirenProbe.expectMessage(new SirenActor.Activate());

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectMessage(new SirenActor.Deactivate());
    }

    @Test
    void controlUnitRejectsBlankConfiguredPin() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        assertThrows(IllegalArgumentException.class, () -> ControlUnitActor.create(" ", sirenProbe.getRef()));
    }

    private void assertState(org.apache.pekko.actor.typed.ActorRef<ControlUnitActor.Command> controlUnit, AlarmState expected) {
        TestProbe<ControlUnitActor.StateSnapshot> stateProbe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(expected, stateProbe.receiveMessage().state());
    }
}
