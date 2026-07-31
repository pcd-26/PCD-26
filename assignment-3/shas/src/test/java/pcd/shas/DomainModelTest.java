package pcd.shas;

import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;

import pcd.shas.sensor.SensorEvent;
import java.time.Instant;

import pcd.shas.keypad.PinSubmitted;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DomainModelTest {

    @Test
    void alarmStateContainsExpectedValues() {
        assertArrayEquals(
                new AlarmState[] {
                        AlarmState.DISARMED,
                        AlarmState.EXIT_DELAY,
                        AlarmState.ARMED,
                        AlarmState.ENTRY_DELAY,
                        AlarmState.ALARM
                },
                AlarmState.values()
        );
    }

    @Test
    void zoneContainsExpectedValues() {
        assertArrayEquals(
                new Zone[] {
                        Zone.PERIMETER,
                        Zone.GROUND_FLOOR,
                        Zone.LIVING_AREA,
                        Zone.SLEEPING_AREA
                },
                Zone.values()
        );
    }

    @Test
    void sensorInfoRejectsBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> new SensorInfo("", SensorType.MOTION, Zone.LIVING_AREA));
        assertThrows(IllegalArgumentException.class, () -> new SensorInfo("sensor-1", null, Zone.LIVING_AREA));
        assertThrows(IllegalArgumentException.class, () -> new SensorInfo("sensor-1", SensorType.DOOR_WINDOW, null));
    }

    @Test
    void sensorInfoExposesImmutableData() {
        SensorInfo sensor = new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER);

        assertEquals("front_door", sensor.id());
        assertEquals(SensorType.DOOR_WINDOW, sensor.type());
        assertEquals(Zone.PERIMETER, sensor.zone());
    }

    @Test
    void sensorEventValidatesAndExposesData() {
        SensorInfo info = new SensorInfo("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER);
        Instant now = Instant.now();
        SensorEvent event = new SensorEvent(info, now);

        assertEquals(info, event.info());
        assertEquals(now, event.timestamp());
        assertThrows(NullPointerException.class, () -> new SensorEvent(null, now));
        assertThrows(NullPointerException.class, () -> new SensorEvent(info, null));
    }

    @Test
    void keypadPinSubmittedValidatesAndExposesData() {
        PinSubmitted event = new PinSubmitted("1234", Set.of("PERIMETER"), null);
        assertEquals("1234", event.pin());
        assertEquals(Set.of("PERIMETER"), event.selectedZones());
        assertThrows(NullPointerException.class, () -> new PinSubmitted(null, Set.of(), null));
        assertThrows(NullPointerException.class, () -> new PinSubmitted("1234", null, null));
    }
}
