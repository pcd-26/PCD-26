package pcd.poool.model.common.math;

/**
 * Immutable 2D vector with common vector operations used by motion/collisions.
 *
 * @param x horizontal component
 * @param y vertical component
 */
public record V2d(double x, double y)  {

    /**
     * Adds another vector to this vector.
     *
     * @param v vector to add
     * @return vector sum
     */
    public V2d sum(V2d v){
        return new V2d(x+v.x,y+v.y);
    }

    /**
     * Computes the vector magnitude.
     *
     * @return Euclidean vector magnitude
     */
    public double abs(){
        return (double)Math.sqrt(x*x+y*y);
    }

    /**
     * Normalizes this vector.
     *
     * @return unit vector with the same direction, or the zero vector when
     *         this vector has zero magnitude
     */
    public V2d getNormalized(){
        double module=(double)Math.sqrt(x*x+y*y);
        if (module == 0) {
            return new V2d(0, 0);
        }
        return new V2d(x/module,y/module);
    }

    /**
     * Multiplies this vector by a scalar.
     *
     * @param fact scalar factor
     * @return scaled vector
     */
    public V2d mul(double fact){
        return new V2d(x*fact,y*fact);
    }

    /**
     * Reflects the vector by reversing its X component.
     *
     * @return vector reflected across the Y axis
     */
    public V2d getSwappedX() {
    	return new V2d(-x, y);
    }

    /**
     * Reflects the vector by reversing its Y component.
     *
     * @return vector reflected across the X axis
     */
    public V2d getSwappedY() {
    	return new V2d(x, -y);
    }

    public String toString(){
        return "V2d("+x+","+y+")";
    }
    
    
}
