package pcd.shas.sensor;

import pcd.shas.common.SensorInfo;
import java.time.Instant;

/**
 * Event emitted by a sensor when it is triggered.
 */
public record SensorEvent(SensorInfo info, Instant timestamp) {}
