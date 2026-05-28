package pcd.poool.view.board;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.*;
import pcd.poool.view.RenderSynch;

public class ViewFrame extends JFrame {
    
    private VisualiserPanel panel;
    private ViewModel model;
    private RenderSynch sync;
    
    public ViewFrame(ViewModel model, int w, int h){
    	this.model = model;
    	this.sync = new RenderSynch();
    	setTitle("Poool");
        setSize(w,h + 25);
        setResizable(false);
        panel = new VisualiserPanel(w,h);
        getContentPane().add(panel);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }
     
    public void render(){
		if (SwingUtilities.isEventDispatchThread()) {
			throw new IllegalStateException("render() must not be called on the EDT");
		}
		long nf = sync.nextFrameToRender();
		panel.setFrameToNotify(nf);
        panel.repaint();
		try {
			sync.waitForFrameRendered(nf);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
    }
        
    public class VisualiserPanel extends JPanel {
        private int ox;
        private int oy;
        private int delta;
        private volatile long frameToNotify = -1;
        
        public VisualiserPanel(int w, int h){
            setSize(w,h + 25);
            ox = w/2;
            oy = h/2;
            delta = Math.min(ox, oy);
        }

        public void setFrameToNotify(long frame) {
        	frameToNotify = frame;
        }

        @Override
        protected void paintComponent(Graphics g){
        	long frame = frameToNotify;
        	try {
	            super.paintComponent(g);
	    		Graphics2D g2 = (Graphics2D) g;
	    		var balls = model.getBalls();
	    		
	    		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
	    		          RenderingHints.VALUE_ANTIALIAS_ON);
	    		g2.setRenderingHint(RenderingHints.KEY_RENDERING,
	    		          RenderingHints.VALUE_RENDER_QUALITY);
	            
	    		g2.setColor(Color.LIGHT_GRAY);
			    g2.setStroke(new BasicStroke(1));
	    		g2.drawLine(ox,0,ox,oy*2);
	    		g2.drawLine(0,oy,ox*2,oy);
	    		g2.setColor(Color.BLACK);
	    		g2.setStroke(new BasicStroke(2));
	    		for (var h: model.getHoles()) {
	    			var p = h.center();
	            	int x0 = (int)(ox + p.x()*delta);
	                int y0 = (int)(oy - p.y()*delta);
	                int radiusX = (int)(h.radius()*delta);
	                int radiusY = (int)(h.radius()*delta);
	                g2.fillOval(x0 - radiusX,y0 - radiusY,radiusX*2,radiusY*2);
	    		}
	    		
			    g2.setStroke(new BasicStroke(1));
	    		for (var b: balls) {
	    			var p = b.pos();
	            	int x0 = (int)(ox + p.x()*delta);
	                int y0 = (int)(oy - p.y()*delta);
	                int radiusX = (int)(b.radius()*delta);
	                int radiusY = (int)(b.radius()*delta);
	                g2.drawOval(x0 - radiusX,y0 - radiusY,radiusX*2,radiusY*2);
	    		}
		
			    g2.setStroke(new BasicStroke(3));
	    		var pb = model.getPlayerBall();
	    		if (pb != null) {
					var p1 = pb.pos();
		        	int x0 = (int)(ox + p1.x()*delta);
		            int y0 = (int)(oy - p1.y()*delta);
	                int radiusX = (int)(pb.radius()*delta);
	                int radiusY = (int)(pb.radius()*delta);
	                g2.drawOval(x0 - radiusX,y0 - radiusY,radiusX*2,radiusY*2);
	    		}
			    
			    g2.setStroke(new BasicStroke(1));
	    		g2.drawString("Num small balls: " + balls.size(), 20, 40);
	    		g2.drawString("Frame per sec: " + model.getFramePerSec(), 20, 60);
        	} finally {
	    		sync.notifyFrameRendered(frame);
        	}
        }
        
    }
}
