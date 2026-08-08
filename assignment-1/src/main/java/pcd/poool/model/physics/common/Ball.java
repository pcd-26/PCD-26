package pcd.poool.model.physics.common;

import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

/**
 * Mutable physical body used by the pool simulation.
 */
public class Ball {

    private static final double REFERENCE_RADIUS = 0.05;
    private static final double REFERENCE_MASS = 1.5;
    private static final double UNIFORM_AREAL_DENSITY =
            REFERENCE_MASS / diskArea(REFERENCE_RADIUS);

    private P2d pos;
    private V2d vel;
    private final double radius;
    private final double mass;

    /** Creates a ball with the exact physical parameters supplied by the caller. */
    public Ball(P2d pos, double radius, double mass, V2d vel) {
        this.pos = pos;
        this.radius = radius;
        this.mass = mass;
        this.vel = vel;
    }

    /** Creates a ball with mass derived from a uniform areal density. */
    public static Ball ofUniformMaterial(P2d pos, double radius, V2d vel) {
        return new Ball(pos, radius, massForRadius(radius), vel);
    }

    /** Computes the mass of a circular ball from its radius. */
    public static double massForRadius(double radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        return UNIFORM_AREAL_DENSITY * diskArea(radius);
    }

    /** Advances the ball by the given time using the board owned by the context. */
    public void updateState(long dt, Board ctx) {
        updateState(dt, ctx.getBounds());
    }

    /** Advances the ball state for one physics slice. */
    public void updateState(long dt, Boundary bounds) {
        double dtSeconds = dt * PhysicsDefaults.SECONDS_PER_MILLISECOND;
        // Slow the ball down before moving it.
        applyFriction(dtSeconds);
        // Integrate the position using the updated velocity.
        move(dtSeconds);
        // Keep the ball inside the table and bounce on walls.
        applyBoundaryConstraints(bounds);
    }

    /** Replaces the current velocity with a new shot impulse. */
    public void kick(V2d vel) {
        this.vel = vel;
    }

    /** Applies a positional correction without changing velocity. */
    public void translate(V2d delta) {
        pos = new P2d(pos.x() + delta.x(), pos.y() + delta.y());
    }

    /** Applies an instantaneous change to the current velocity. */
    public void addVelocity(V2d delta) {
        vel = vel.sum(delta);
    }

    private void applyBoundaryConstraints(Boundary bounds) {
        // Hit the right wall.
        if (pos.x() + radius > bounds.x1()) {
            pos = new P2d(bounds.x1() - radius, pos.y());
            vel = vel.getSwappedX();
        // Hit the left wall.
        } else if (pos.x() - radius < bounds.x0()) {
            pos = new P2d(bounds.x0() + radius, pos.y());
            vel = vel.getSwappedX();
        // Hit the top wall.
        } else if (pos.y() + radius > bounds.y1()) {
            pos = new P2d(pos.x(), bounds.y1() - radius);
            vel = vel.getSwappedY();
        // Hit the bottom wall.
        } else if (pos.y() - radius < bounds.y0()) {
            pos = new P2d(pos.x(), bounds.y0() + radius);
            vel = vel.getSwappedY();
        }
    }

    public static void resolveCollision(Ball a, Ball b) {
        // Measure the vector between centers.
        double dx = b.pos.x() - a.pos.x();
        double dy = b.pos.y() - a.pos.y();
        double dist = Math.hypot(dx, dy);
        double minDistance = a.radius + b.radius;

        // Only handle real overlaps.
        if (dist < minDistance) {
            // Avoid division by zero when the centers almost coincide.
            if (dist <= PhysicsDefaults.COINCIDENT_CENTER_EPSILON) {
                dx = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
                dy = 0.0;
                dist = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
            }

            // Normalize the collision direction.
            double nx = dx / dist;
            double ny = dy / dist;

            // Push the balls apart before changing their velocities.
            separateOverlap(a, b, nx, ny, minDistance - dist);
            // Apply the elastic response only if they are closing in.
            applyElasticImpulse(a, b, nx, ny);
        }
    }

    /**
     * Applies linear friction over the current time slice.
     */
    private void applyFriction(double dtSeconds) {
        double speed = vel.abs();
        if (speed > PhysicsDefaults.REST_SPEED_THRESHOLD) {
            // Reduce the speed by a constant amount proportional to dt.
            double deceleration = PhysicsDefaults.FRICTION_DECELERATION * dtSeconds;
            double factor = Math.max(0, speed - deceleration) / speed;
            vel = vel.mul(factor);
        } else {
            // Snap tiny residual velocities to rest.
            vel = new V2d(0, 0);
        }
    }

    /** Integrates the position using the current velocity. */
    private void move(double dtSeconds) {
        pos = pos.sum(vel.mul(dtSeconds));
    }

    private static void separateOverlap(Ball a, Ball b, double nx, double ny, double overlap) {
        double totalMass = a.mass + b.mass;

        // The heavier ball is displaced less.
        double aDisplacement = overlap * (b.mass / totalMass);
        a.pos = new P2d(
                a.getPos().x() - nx * aDisplacement,
                a.getPos().y() - ny * aDisplacement);

        // Move the other ball in the opposite direction.
        double bDisplacement = overlap * (a.mass / totalMass);
        b.pos = new P2d(
                b.getPos().x() + nx * bDisplacement,
                b.getPos().y() + ny * bDisplacement);
    }

    private static void applyElasticImpulse(Ball a, Ball b, double nx, double ny) {
        // Relative velocity projected on the collision normal.
        double relativeVelocityX = b.vel.x() - a.vel.x();
        double relativeVelocityY = b.vel.y() - a.vel.y();
        double relativeVelocityAlongNormal = relativeVelocityX * nx + relativeVelocityY * ny;

        // If the balls are separating, keep the current velocities.
        if (relativeVelocityAlongNormal > 0) {
            return;
        }

        // Compute the impulse for an elastic collision.
        double impulse = -(1 + PhysicsDefaults.RESTITUTION_FACTOR) * relativeVelocityAlongNormal
                / (1.0 / a.getMass() + 1.0 / b.getMass());

        // Apply opposite velocity changes to both balls.
        a.vel = new V2d(a.vel.x() - (impulse / a.mass) * nx, a.vel.y() - (impulse / a.mass) * ny);
        b.vel = new V2d(b.vel.x() + (impulse / b.mass) * nx, b.vel.y() + (impulse / b.mass) * ny);
    }

    public P2d getPos() {
        return pos;
    }

    public double getMass() {
        return mass;
    }

    public V2d getVel() {
        return vel;
    }

    public double getRadius() {
        return radius;
    }

    /** Returns whether the ball is still considered in motion. */
    public boolean isMoving() {
        return vel.abs() > PhysicsDefaults.REST_SPEED_THRESHOLD;
    }

    /** Computes the area of the ball cross-section. */
    private static double diskArea(double radius) {
        return Math.PI * radius * radius;
    }
}
