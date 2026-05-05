package pcd.sketch01;


public record V2d(double x, double y)  {

    public V2d sum(V2d v){
        return new V2d(x+v.x,y+v.y);
    }

    public double abs(){
        return (double)Math.sqrt(x*x+y*y);
    }

    public V2d getNormalized(){
        double module=(double)Math.sqrt(x*x+y*y);
        return new V2d(x/module,y/module);
    }

    public V2d mul(double fact){
        return new V2d(x*fact,y*fact);
    }

    public V2d getSwappedX() {
    	return new V2d(-x, y);
    }

    public V2d getSwappedY() {
    	return new V2d(x, -y);
    }

    public String toString(){
        return "V2d("+x+","+y+")";
    }
    
    
}
