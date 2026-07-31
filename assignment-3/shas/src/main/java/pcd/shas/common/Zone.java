package pcd.shas.common;

/**
 * Logical zone where a sensor is installed.
 */
public enum Zone {
    /**
     * Outer perimeter zone (e.g. external doors, windows, gates).
     */
    PERIMETER,

    /**
     * Ground floor indoor area.
     */
    GROUND_FLOOR,

    /**
     * Living room and shared common areas.
     */
    LIVING_AREA,

    /**
     * Bedrooms and private sleeping areas.
     */
    SLEEPING_AREA
}
