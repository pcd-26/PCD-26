package pcd.shas;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.controlunit.ControlUnitActor;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlUnitActorTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setUp() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    void initialStateIsDisarmed() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofMillis(50), Duration.ofMillis(50)));

        assertState(controlUnit, AlarmState.DISARMED);
    }

    @Test
    void correctPinMovesSystemToExitDelay() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofMillis(50), Duration.ofMillis(50)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(controlUnit, AlarmState.EXIT_DELAY);
    }

    @Test
    void incorrectPinHasNoEffectWhileDisarmed() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofMillis(50), Duration.ofMillis(50)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("0000"));

        assertState(controlUnit, AlarmState.DISARMED);
    }

    @Test
    void exitDelayIgnoresPinSubmissionsUntilExpiration() throws Exception {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofMillis(40), Duration.ofMillis(40)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.PinSubmitted("0000"));

        assertState(controlUnit, AlarmState.EXIT_DELAY);

        controlUnit.tell(new ControlUnitActor.ExitDelayExpired());

        assertState(controlUnit, AlarmState.ARMED);
    }

    @Test
    void armedSensorMovesSystemToEntryDelay() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofMillis(40), Duration.ofMillis(40)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayExpired());

        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter")));

        assertState(controlUnit, AlarmState.ENTRY_DELAY);
    }

    @Test
    void entryDelayExpiresIntoAlarmAndCorrectPinDisarms() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofMillis(40), Duration.ofMillis(40)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayExpired());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter")));

        assertState(controlUnit, AlarmState.ENTRY_DELAY);

        controlUnit.tell(new ControlUnitActor.EntryDelayExpired());

        assertState(controlUnit, AlarmState.ALARM);

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));

        assertState(controlUnit, AlarmState.DISARMED);
    }

    @Test
    void incorrectPinHasNoEffectInAlarm() {
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofMillis(40), Duration.ofMillis(40)));

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayExpired());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter")));
        controlUnit.tell(new ControlUnitActor.EntryDelayExpired());

        controlUnit.tell(new ControlUnitActor.PinSubmitted("0000"));

        assertState(controlUnit, AlarmState.ALARM);
    }

    @Test
    void controlUnitRejectsBlankConfiguredPin() {
        assertThrows(IllegalArgumentException.class, () -> ControlUnitActor.create(" "));
    }

    private static void assertState(org.apache.pekko.actor.typed.ActorRef<ControlUnitActor.Command> controlUnit, AlarmState expected) {
        TestProbe<ControlUnitActor.StateSnapshot> stateProbe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(expected, stateProbe.receiveMessage().state());
    }
}
