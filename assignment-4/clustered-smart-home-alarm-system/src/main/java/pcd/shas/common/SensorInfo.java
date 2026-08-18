package pcd.shas.common;

public record SensorInfo(String id, SensorType type, Zone zone) implements MySerializable {
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
