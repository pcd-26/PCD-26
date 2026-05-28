package pcd.poool.model.physics;

import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;

/**
 * Ball entity with position, velocity, and collision update logic.
 */
public class Ball {
    
    private P2d pos;
    private V2d vel;
    private final double radius;
    private final double mass;

    public Ball(P2d pos, double radius, double mass, V2d vel){
       this.pos = pos;
       this.radius = radius;
       this.mass = mass;
       this.vel = vel;
    }

    public void updateState(long dt, Board ctx){
        updateState(dt, ctx.getBounds());
    }

    public void updateState(long dt, Boundary bounds){
        double dt_scaled = dt * PhysicsDefaults.SECONDS_PER_MILLISECOND;
        applyFriction(dt_scaled);
        move(dt_scaled);
     	applyBoundaryConstraints(bounds);
    }
    
    public void kick(V2d vel) {
    	this.vel = vel;
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

    
    public P2d getPos(){        
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

    public boolean isMoving() {
        return vel.abs() > PhysicsDefaults.REST_SPEED_THRESHOLD;
    }

}
