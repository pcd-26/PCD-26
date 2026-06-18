package pcd.poool.model.physics.common;

import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

/**
 * Ball entity with position, velocity, and collision update logic.
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

    /**
     * Creates a mutable physical ball.
     *
     * @param pos initial center position
     * @param radius ball radius
     * @param mass ball mass used by collision impulses
     * @param vel initial velocity
     */
    public Ball(P2d pos, double radius, double mass, V2d vel){
       this.pos = pos;
       this.radius = radius;
       this.mass = mass;
       this.vel = vel;
    }

    /**
     * Creates a ball assuming the same material density used by the standard
     * cue ball, so mass scales with the disk area in the 2D simulation.
     *
     * @param pos initial center position
     * @param radius ball radius
     * @param vel initial velocity
     * @return ball whose mass is derived from its radius
     */
    public static Ball ofUniformMaterial(P2d pos, double radius, V2d vel) {
        return new Ball(pos, radius, massForRadius(radius), vel);
    }

    /**
     * Computes the mass implied by the uniform-material 2D model.
     *
     * @param radius ball radius
     * @return mass proportional to the disk area with shared areal density
     */
    public static double massForRadius(double radius) {
        if (radius <= 0) {
            throw new IllegalArgumentException("radius must be > 0");
        }
        return UNIFORM_AREAL_DENSITY * diskArea(radius);
    }

    /**
     * Advances this ball using the boundary owned by a board.
     *
     * @param dt elapsed time in milliseconds
     * @param ctx board that provides movement bounds
     */
    public void updateState(long dt, Board ctx){
        updateState(dt, ctx.getBounds());
    }

    /**
     * Advances this ball by applying friction, movement, and wall bounces.
     *
     * @param dt elapsed time in milliseconds
     * @param bounds rectangular movement boundary
     */
    public void updateState(long dt, Boundary bounds){
        double dt_scaled = dt * PhysicsDefaults.SECONDS_PER_MILLISECOND;
        applyFriction(dt_scaled);
        move(dt_scaled);
     	applyBoundaryConstraints(bounds);
    }
    
    /**
     * Assigns a new velocity to the ball.
     *
     * @param vel new velocity
     */
    public void kick(V2d vel) {
    	this.vel = vel;
    }

    public void translate(V2d delta) {
        pos = new P2d(pos.x() + delta.x(), pos.y() + delta.y());
    }

    public void addVelocity(V2d delta) {
        vel = vel.sum(delta);
    }

    private void applyBoundaryConstraints(Boundary bounds){
        if (pos.x() + radius > bounds.x1()){
            pos = new P2d(bounds.x1() - radius, pos.y());
            vel = vel.getSwappedX();
        } else if (pos.x() - radius < bounds.x0()){
            pos = new P2d(bounds.x0() + radius, pos.y());
            vel = vel.getSwappedX();
        } else if (pos.y() + radius > bounds.y1()){
            pos = new P2d(pos.x(), bounds.y1() - radius);
            vel = vel.getSwappedY();
        } else if (pos.y() - radius < bounds.y0()){
            pos = new P2d(pos.x(), bounds.y0() + radius);
            vel = vel.getSwappedY();
        }
    }

    /**
     * Resolves one elastic collision and removes geometric overlap.
     *
     * <p>The method is deterministic for coincident centers: it chooses the
     * positive X axis as the separation normal, avoiding undefined normals.
     *
     * @param a first colliding ball
     * @param b second colliding ball
     */
    public static void resolveCollision(Ball a, Ball b) {
    	double dx   = b.pos.x() - a.pos.x();
        double dy   = b.pos.y() - a.pos.y();
        double dist = Math.hypot(dx, dy);
        double minD = a.radius + b.radius;

        if (dist < minD)  {
            if (dist <= PhysicsDefaults.COINCIDENT_CENTER_EPSILON) {
                dx = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
                dy = 0.0;
                dist = PhysicsDefaults.COINCIDENT_CENTER_EPSILON;
            }

        	double nx = dx / dist;
	        double ny = dy / dist;
            separateOverlap(a, b, nx, ny, minD - dist);
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

    
    /**
     * Gets the current center position.
     *
     * @return current center position
     */
    public P2d getPos(){        
    	return pos;
    }
    
    /**
     * Gets the mass used by impulse computations.
     *
     * @return mass used by elastic collision resolution
     */
    public double getMass() {
    	return mass;
    }
    
    /**
     * Gets the current velocity.
     *
     * @return current velocity
     */
    public V2d getVel() {
    	return vel;
    }
    
    /**
     * Gets the ball radius.
     *
     * @return ball radius
     */
    public double getRadius() {
    	return radius;
    }

    /**
     * Checks whether this ball is still moving.
     *
     * @return whether the ball velocity is above the rest threshold
     */
    public boolean isMoving() {
        return vel.abs() > PhysicsDefaults.REST_SPEED_THRESHOLD;
    }

    private static double diskArea(double radius) {
        return Math.PI * radius * radius;
    }

}
