package pcd.shas.sensor;

import pcd.shas.common.SensorInfo;
import java.time.Instant;
import java.util.Objects;

/**
 * Event emitted by a sensor when it is triggered.
 */
public record SensorEvent(SensorInfo info, Instant timestamp) {
    public SensorEvent {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}

