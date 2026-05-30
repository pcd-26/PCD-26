package pcd.poool.model.physics;

/**
 * Shared physics constants used by the deterministic sequential engine.
 */
public final class PhysicsDefaults {

    public static final long FIXED_STEP_MILLIS = 16;
    public static final double SECONDS_PER_MILLISECOND = 0.001;
    public static final double FRICTION_DECELERATION = 0.25;
    public static final double RESTITUTION_FACTOR = 1.0;
    public static final double REST_SPEED_THRESHOLD = 0.001;
    public static final double COINCIDENT_CENTER_EPSILON = 1e-9;
    public static final double MIN_SPATIAL_CELL_SIZE = 0.0001;
    public static final double DEFAULT_HOLE_RADIUS = 0.16;
    public static final double RADIUS_TO_DIAMETER = 2.0;

    private PhysicsDefaults() {
    }
}
