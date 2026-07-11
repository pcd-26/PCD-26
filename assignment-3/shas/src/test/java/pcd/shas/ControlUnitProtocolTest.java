package pcd.shas;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.controlunit.ControlUnitActor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlUnitProtocolTest {

    @Test
    void pinSubmissionCopiesSelectedZones() {
        ControlUnitActor.PinSubmitted message = new ControlUnitActor.PinSubmitted("1234", Set.of("Perimeter"));

        assertEquals("1234", message.pin());
        assertEquals(Set.of("Perimeter"), message.selectedZones());
        assertThrows(UnsupportedOperationException.class, () -> message.selectedZones().add("Living Area"));
    }

    @Test
    void queryStateCarriesReplyActor() {
        ActorTestKit testKit = ActorTestKit.create();
        try {
            var stateProbe = testKit.createTestProbe(ControlUnitActor.StateSnapshot.class);
            ControlUnitActor.QueryState query = new ControlUnitActor.QueryState(stateProbe.getRef());

            assertEquals(stateProbe.getRef(), query.replyTo());
        } finally {
            testKit.shutdownTestKit();
        }
    }

    @Test
    void stateSnapshotCopiesActiveZones() {
        ControlUnitActor.StateSnapshot snapshot = new ControlUnitActor.StateSnapshot(
                AlarmState.ARMED,
                false,
                Set.of("Perimeter")
        );

        assertEquals(AlarmState.ARMED, snapshot.state());
        assertEquals(Set.of("Perimeter"), snapshot.activeZones());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.activeZones().add("Living Area"));
    }

    @Test
    void sensorActivatedCarriesSensorInfo() {
        SensorInfo sensor = new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter");
        ControlUnitActor.SensorActivated activated = new ControlUnitActor.SensorActivated(sensor);

        assertEquals(sensor, activated.sensorInfo());
    }
}
