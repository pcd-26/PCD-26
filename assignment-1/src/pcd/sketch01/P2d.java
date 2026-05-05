package pcd.sketch01;

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

    public double x() {
    	return x;
    }

    public double y() {
    	return y;
    }
}

