package pcd.poool.model.physics;

/**
 * Shared physics constants used by the deterministic sequential engine.
 */
public final class PhysicsDefaults {

    /** Fixed simulation tick used by default runners, in milliseconds. */
    public static final long FIXED_STEP_MILLIS = 16;
    /** Conversion factor from milliseconds to seconds. */
    public static final double SECONDS_PER_MILLISECOND = 0.001;
    /** Constant deceleration applied to moving balls. */
    public static final double FRICTION_DECELERATION = 0.25;
    /** Elastic restitution coefficient for ball collisions. */
    public static final double RESTITUTION_FACTOR = 1.0;
    /** Velocity magnitude below which a ball is considered stopped. */
    public static final double REST_SPEED_THRESHOLD = 0.001;
    /** Tolerance used when two colliding ball centers are numerically equal. */
    public static final double COINCIDENT_CENTER_EPSILON = 1e-9;
    /** Lower bound for spatial-grid cell size. */
    public static final double MIN_SPATIAL_CELL_SIZE = 0.0001;
    /** Radius used by default board holes. */
    public static final double DEFAULT_HOLE_RADIUS = 0.16;
    /** Conversion factor from radius to diameter. */
    public static final double RADIUS_TO_DIAMETER = 2.0;

    private PhysicsDefaults() {
    }
}
