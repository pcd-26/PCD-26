package pcd.shas;

import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;

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
}
