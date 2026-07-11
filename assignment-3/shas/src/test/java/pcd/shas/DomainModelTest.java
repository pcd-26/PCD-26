package pcd.shas;

import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;

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
    void sensorInfoRejectsBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> new SensorInfo("", SensorType.MOTION, "Zone"));
        assertThrows(IllegalArgumentException.class, () -> new SensorInfo("sensor-1", null, "Zone"));
        assertThrows(IllegalArgumentException.class, () -> new SensorInfo("sensor-1", SensorType.DOOR_WINDOW, " "));
    }

    @Test
    void sensorInfoExposesImmutableData() {
        SensorInfo sensor = new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter");

        assertEquals("front_door", sensor.id());
        assertEquals(SensorType.DOOR_WINDOW, sensor.type());
        assertEquals("Perimeter", sensor.zone());
    }
}
