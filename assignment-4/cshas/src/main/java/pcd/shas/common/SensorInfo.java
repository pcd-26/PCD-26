package pcd.shas.common;

/**
 * Immutable sensor metadata exchanged between distributed actors.
 *
 * @param id   the stable sensor identifier
 * @param type the sensor type
 * @param zone the installation zone
 */
public record SensorInfo(String id, SensorType type, Zone zone) implements MySerializable {
    /**
     * Validates the sensor metadata used in remote messages.
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
