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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ControlUnitZoneArmingTest {

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
    void fullArmingKeepsAllZonesActive() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.RequestFullArming("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        assertState(controlUnit, AlarmState.ARMED);

        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("living_room_motion", SensorType.MOTION, Zone.LIVING_AREA)));

        assertState(controlUnit, AlarmState.ENTRY_DELAY);
        sirenProbe.expectNoMessage();
    }

    @Test
    void partialArmingAllowsOnlySelectedZones() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.RequestPartialArming("1234", Set.of(Zone.PERIMETER, Zone.GROUND_FLOOR)));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        assertState(controlUnit, AlarmState.ARMED);

        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("living_room_motion", SensorType.MOTION, Zone.LIVING_AREA)));

        assertState(controlUnit, AlarmState.ARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void activeZoneIntrusionEntersEntryDelay() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.RequestPartialArming("1234", Set.of(Zone.PERIMETER, Zone.GROUND_FLOOR)));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER)));

        assertState(controlUnit, AlarmState.ENTRY_DELAY);
        sirenProbe.expectNoMessage();
    }

    @Test
    void inactiveZoneEventIsIgnored() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.RequestPartialArming("1234", Set.of(Zone.PERIMETER)));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("living_room_motion", SensorType.MOTION, Zone.LIVING_AREA)));

        assertState(controlUnit, AlarmState.ARMED);
        sirenProbe.expectNoMessage();
    }

    @Test
    void nightModeConfigurationResetsToFullArmingAfterDisarm() {
        var sirenProbe = testKit.createTestProbe(SirenActor.Command.class);
        var controlUnit = testKit.spawn(ControlUnitActor.create("1234", Duration.ofSeconds(1), Duration.ofSeconds(1), sirenProbe.getRef()));

        controlUnit.tell(new ControlUnitActor.RequestPartialArming("1234", Set.of(Zone.PERIMETER)));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("living_room_motion", SensorType.MOTION, Zone.LIVING_AREA)));
        assertState(controlUnit, AlarmState.ARMED);

        controlUnit.tell(new ControlUnitActor.PinSubmitted("1234"));
        assertState(controlUnit, AlarmState.DISARMED);
        sirenProbe.expectMessage(new SirenActor.Deactivate());

        controlUnit.tell(new ControlUnitActor.RequestFullArming("1234"));
        controlUnit.tell(new ControlUnitActor.ExitDelayTimeout());
        assertState(controlUnit, AlarmState.ARMED);

        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo("living_room_motion", SensorType.MOTION, Zone.LIVING_AREA)));

        assertState(controlUnit, AlarmState.ENTRY_DELAY);
        sirenProbe.expectNoMessage();
    }

    private void assertState(org.apache.pekko.actor.typed.ActorRef<ControlUnitActor.Command> controlUnit, AlarmState expected) {
        TestProbe<ControlUnitActor.StateSnapshot> stateProbe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);
        controlUnit.tell(new ControlUnitActor.QueryState(stateProbe.getRef()));
        assertEquals(expected, stateProbe.receiveMessage().state());
    }
}
