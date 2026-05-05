package pcd.sketch01;

import java.util.ArrayList;
import java.util.List;

public class LargeBoardConf implements BoardConf {

	@Override
	public Ball getPlayerBall() {
		return  new Ball(new P2d(0, -0.75), 0.05, 1.5, new V2d(0,1)); 
	}

	@Override
	public List<Ball> getSmallBalls() {		
		var ballRadius = 0.01;
        var balls = new ArrayList<Ball>();

    	for (int row = 0; row < 20; row++) {
    		for (int col = 0; col < 20; col++) {
        		var px = -0.25 + col*0.025;
        		var py =  row*0.025;
        		var b = new Ball(new P2d(px, py), ballRadius, 0.25, new V2d(0,0));
            	balls.add(b);    			
    		}
    	}		
    	return balls;
	}

	public Boundary getBoardBoundary() {
        return new Boundary(-1.5,-1.0,1.5,1.0);
	}
}
