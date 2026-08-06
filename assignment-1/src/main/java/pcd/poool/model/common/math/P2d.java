package pcd.poool.model.common.math;

/**
 * Immutable 2D point used by the physics model.
 */
public record P2d(double x, double y)  {

    public P2d sum(V2d v){
        return new P2d(x+v.x(),y+v.y());
    }

    public V2d sub(P2d v){
        return new V2d(x-v.x(),y-v.y());
    }
    
    public String toString(){
        return "P2d("+x+","+y+")";
    }
}

