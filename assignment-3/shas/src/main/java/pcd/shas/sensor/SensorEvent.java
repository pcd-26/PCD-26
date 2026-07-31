package pcd.shas.sensor;

import pcd.shas.common.SensorInfo;
import java.time.Instant;
import java.util.Objects;

/**
 * Event emitted by a sensor when it is triggered.
 *
 * @param info metadata describing the sensor that triggered the event
 * @param timestamp exact instant when the activation occurred
 */
public record SensorEvent(SensorInfo info, Instant timestamp) {
    /**
     * Compact constructor validating that sensor info and timestamp parameters are non-null.
     *
     * @throws NullPointerException if {@code info} or {@code timestamp} is null
     */
    public SensorEvent {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}

