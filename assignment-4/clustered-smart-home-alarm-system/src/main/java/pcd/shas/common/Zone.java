package pcd.shas.common;

/**
 * Physical zone used by the alarm state machine to decide which sensors are
 * active while the system is armed.
 */
public enum Zone {
    /**
     * Perimeter zone (doors, windows).
     */
    PERIMETER,

    /**
     * Ground floor sensors.
     */
    GROUND_FLOOR,

    /**
     * Living area sensors.
     */
    LIVING_AREA,

    /**
     * Sleeping area sensors.
     */
    SLEEPING_AREA
}
