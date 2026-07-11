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
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(5), Duration.ofSeconds(5)));

        assertState(controlUnit, AlarmState.DISARMED);
    }

    @Test
    void correctPinMovesSystemToExitDelayAndExitTimeoutArmsSystem() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(controlUnit, AlarmState.EXIT_DELAY);

        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.ARMED);
    }

    @Test
    void incorrectPinHasNoEffectWhileDisarmed() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("0000"));

        assertState(controlUnit, AlarmState.DISARMED);
    }

    @Test
    void leavingExitDelayCancelsObsoleteTimeout() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(controlUnit, AlarmState.EXIT_DELAY);

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(controlUnit, AlarmState.DISARMED);

        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.DISARMED);
    }

    @Test
    void armedSensorMovesSystemToEntryDelayAndEntryTimeoutAlarmsSystem() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.ARMED);

        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter")));
        assertState(controlUnit, AlarmState.ENTRY_DELAY);

        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.ALARM);
    }

    @Test
    void entryDelayPinDisarmsBeforeTimeout() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        manualTime.timePasses(Duration.ofSeconds(1));
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter")));
        assertState(controlUnit, AlarmState.ENTRY_DELAY);

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(controlUnit, AlarmState.DISARMED);

        manualTime.timePasses(Duration.ofSeconds(1));
        assertState(controlUnit, AlarmState.DISARMED);
    }

    @Test
    void incorrectPinHasNoEffectInAlarm() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        manualTime.timePasses(Duration.ofSeconds(1));
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter")));
        manualTime.timePasses(Duration.ofSeconds(1));

        assertState(controlUnit, AlarmState.ALARM);

        controlUnit.tell(new ControlUnitActor.PinSubmitted("0000"));

        assertState(controlUnit, AlarmState.ALARM);
    }

    @Test
    void controlUnitRejectsBlankConfiguredPin() {
        assertThrows(IllegalArgumentException.class, () -> ControlUnitActor.create(" "));
    }

    private void assertState(org.apache.pekko.actor.typed.ActorRef<ControlUnitActor.Command> controlUnit, AlarmState expected) {
        TestProbe<ControlUnitActor.StateSnapshot> stateProbe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(expected, stateProbe.receiveMessage().state());
    }
}
