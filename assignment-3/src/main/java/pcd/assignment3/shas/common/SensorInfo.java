package pcd.assignment3.shas.common;

/**
 * An immutable record representing the configuration and metadata of a sensor.
 *
 * @param id   the unique identifier of the sensor
 * @param type the type of the sensor (e.g., MOTION, DOOR_WINDOW)
 * @param zone the zone where the sensor is installed (e.g., Perimeter, Living Area)
 */
public record SensorInfo(String id, SensorType type, String zone) {
    /**
     * Compact constructor validating that the sensor information parameters are not null or empty.
     */
    public SensorInfo {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Sensor ID cannot be null or empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("Sensor type cannot be null");
        }
        if (zone == null || zone.isBlank()) {
            throw new IllegalArgumentException("Sensor zone cannot be null or empty");
        }
    }
}
