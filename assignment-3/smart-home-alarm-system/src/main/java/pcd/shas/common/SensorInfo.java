package pcd.shas.common;

/**
 * An immutable record representing the configuration and metadata of a sensor.
 *
 * @param id   the unique identifier of the sensor
 * @param type the type of the sensor (e.g., MOTION, DOOR_WINDOW)
 * @param zone the zone where the sensor is installed
 */
public record SensorInfo(String id, SensorType type, Zone zone) {
    /**
     * Compact constructor validating that the sensor information parameters are non-null and non-blank.
     *
     * @throws IllegalArgumentException if {@code id} is null/blank, or if {@code type} or {@code zone} is null
     */
    public SensorInfo {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Sensor ID cannot be null or empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("Sensor type cannot be null");
        }
        if (zone == null) {
            throw new IllegalArgumentException("Sensor zone cannot be null");
        }
    }
}
