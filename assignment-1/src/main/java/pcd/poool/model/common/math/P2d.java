package pcd.poool.model.common.math;

/**
 * Immutable 2D point used by the physics model.
 *
 * @param x horizontal coordinate
 * @param y vertical coordinate
 */
public record P2d(double x, double y)  {

    /**
     * Translates this point by a vector.
     *
     * @param v displacement vector
     * @return translated point
     */
    public P2d sum(V2d v){
        return new P2d(x+v.x(),y+v.y());
    }

    /**
     * Computes the vector from another point to this point.
     *
     * @param v origin point
     * @return difference vector
     */
    public V2d sub(P2d v){
        return new V2d(x-v.x(),y-v.y());
    }
    
    public String toString(){
        return "P2d("+x+","+y+")";
    }

    /**
     * Gets the horizontal coordinate.
     *
     * @return horizontal coordinate
     */
    public double x() {
    	return x;
    }

    /**
     * Gets the vertical coordinate.
     *
     * @return vertical coordinate
     */
    public double y() {
    	return y;
    }
}

