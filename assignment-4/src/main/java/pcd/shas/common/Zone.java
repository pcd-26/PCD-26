package pcd.shas.common;

/**
 * Logical zone where a sensor is installed.
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
