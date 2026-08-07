package pcd.poool.model.physics.common;

import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

// Physical ball with position, velocity, mass, and radius.
public class Ball {

    private static final double REFERENCE_RADIUS = 0.05;
    private static final double REFERENCE_MASS = 1.5;
    private static final double UNIFORM_AREAL_DENSITY =
            REFERENCE_MASS / diskArea(REFERENCE_RADIUS);

    private P2d pos;
    private V2d vel;
    private final double radius;
    private final double mass;

    // Creates a mutable physical ball.
    public Ball(P2d pos, double radius, double mass, V2d vel) {
        this.pos = pos;
        this.radius = radius;
        this.mass = mass;
        this.vel = vel;
    }

    // Creates a ball using the same material density as the reference cue ball.
    public static Ball ofUniformMaterial(P2d pos, double radius, V2d vel) {
        return new Ball(pos, radius, massForRadius(radius), vel);
    }

    // Mass is proportional to disk area with shared areal density.
    public static double massForRadius(double radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        return UNIFORM_AREAL_DENSITY * diskArea(radius);
    }

    // Uses the board boundary to advance the ball.
    public void updateState(long dt, Board ctx) {
        updateState(dt, ctx.getBounds());
    }

    // Applies friction, movement, and wall bounces.
    public void updateState(long dt, Boundary bounds) {
        double dtScaled = dt * PhysicsDefaults.SECONDS_PER_MILLISECOND;
        applyFriction(dtScaled);
        move(dtScaled);
        applyBoundaryConstraints(bounds);
    }

    // Sets the current velocity.
    public void kick(V2d vel) {
        this.vel = vel;
    }

    // Translates the ball position.
    public void translate(V2d delta) {
        pos = new P2d(pos.x() + delta.x(), pos.y() + delta.y());
    }

    // Adds a velocity delta.
    public void addVelocity(V2d delta) {
        vel = vel.sum(delta);
    }

    // Reflects the ball on the rectangular boundary.
    private void applyBoundaryConstraints(Boundary bounds) {
        if (pos.x() + radius > bounds.x1()) {
            pos = new P2d(bounds.x1() - radius, pos.y());
            vel = vel.getSwappedX();
        } else if (pos.x() - radius < bounds.x0()) {
            pos = new P2d(bounds.x0() + radius, pos.y());
            vel = vel.getSwappedX();
        } else if (pos.y() + radius > bounds.y1()) {
            pos = new P2d(pos.x(), bounds.y1() - radius);
            vel = vel.getSwappedY();
        } else if (pos.y() - radius < bounds.y0()) {
            pos = new P2d(pos.x(), bounds.y0() + radius);
            vel = vel.getSwappedY();
        }
    }

    // Separates overlapping balls and applies the elastic impulse.
    public static void resolveCollision(Ball a, Ball b) {
        double dx = b.pos.x() - a.pos.x();
        double dy = b.pos.y() - a.pos.y();
        double dist = Math.hypot(dx, dy);
        double minDistance = a.radius + b.radius;

        if (dist < minDistance) {
            if (dist <= PhysicsDefaults.COINCIDENT_CENTER_EPSILON) {
                dx = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
                dy = 0.0;
                dist = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
            }

            double nx = dx / dist;
            double ny = dy / dist;

            separateOverlap(a, b, nx, ny, minDistance - dist);

            applyElasticImpulse(a, b, nx, ny);
        }
    }

    private void applyFriction(double dtScaled) {
        double speed = vel.abs();
        if (speed > PhysicsDefaults.REST_SPEED_THRESHOLD) {
            double deceleration = PhysicsDefaults.FRICTION_DECELERATION * dtScaled;
            double factor = Math.max(0, speed - deceleration) / speed;
            vel = vel.mul(factor);
        } else {
            vel = new V2d(0, 0);
        }
    }

    private void move(double dtScaled) {
        pos = pos.sum(vel.mul(dtScaled));
    }

    private static void separateOverlap(Ball a, Ball b, double nx, double ny, double overlap) {
        double totalMass = a.mass + b.mass;

        double aDisplacement = overlap * (b.mass / totalMass);
        a.pos = new P2d(
                a.getPos().x() - nx * aDisplacement,
                a.getPos().y() - ny * aDisplacement);

        double bDisplacement = overlap * (a.mass / totalMass);
        b.pos = new P2d(
                b.getPos().x() + nx * bDisplacement,
                b.getPos().y() + ny * bDisplacement);
    }

    private static void applyElasticImpulse(Ball a, Ball b, double nx, double ny) {
        double relativeVelocityX = b.vel.x() - a.vel.x();
        double relativeVelocityY = b.vel.y() - a.vel.y();
        double relativeVelocityAlongNormal = relativeVelocityX * nx + relativeVelocityY * ny;

        if (relativeVelocityAlongNormal > 0) {
            return;
        }

        double impulse = -(1 + PhysicsDefaults.RESTITUTION_FACTOR) * relativeVelocityAlongNormal
                / (1.0 / a.getMass() + 1.0 / b.getMass());

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

    // True when the speed is above the rest threshold.
    public boolean isMoving() {
        return vel.abs() > PhysicsDefaults.REST_SPEED_THRESHOLD;
    }

    // Disk area used by the uniform-density mass model.
    private static double diskArea(double radius) {
        return Math.PI * radius * radius;
    }
}
