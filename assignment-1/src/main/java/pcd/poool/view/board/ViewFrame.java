package pcd.poool.view.board;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.*;
import pcd.poool.model.common.math.P2d;
import pcd.poool.view.RenderSynch;

public class ViewFrame extends JFrame {

    private static final String WINDOW_TITLE = "Poool";
    private static final int WINDOW_DECORATION_HEIGHT = 25;
    private static final long NO_FRAME_TO_NOTIFY = -1;
    private static final int AXIS_STROKE_WIDTH = 1;
    private static final int HOLE_STROKE_WIDTH = 2;
    private static final int SMALL_BALL_STROKE_WIDTH = 1;
    private static final int PLAYER_BALL_STROKE_WIDTH = 3;
    private static final int HUD_X = 20;
    private static final int HUD_BALL_COUNT_Y = 40;
    private static final int HUD_FPS_Y = 60;
    private static final String SMALL_BALL_COUNT_LABEL = "Num small balls: ";
    private static final String FPS_LABEL = "Frame per sec: ";
    private static final int CIRCLE_DIAMETER_FACTOR = 2;
    
    private VisualiserPanel panel;
    private ViewModel model;
    private RenderSynch sync;
    
    public ViewFrame(ViewModel model, int w, int h){
    	this.model = model;
    	this.sync = new RenderSynch();
    	setTitle(WINDOW_TITLE);
        setSize(w, h + WINDOW_DECORATION_HEIGHT);
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
        private volatile long frameToNotify = NO_FRAME_TO_NOTIFY;
        
        public VisualiserPanel(int w, int h){
            setSize(w, h + WINDOW_DECORATION_HEIGHT);
            ox = w / CIRCLE_DIAMETER_FACTOR;
            oy = h / CIRCLE_DIAMETER_FACTOR;
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
			    g2.setStroke(new BasicStroke(AXIS_STROKE_WIDTH));
	    		g2.drawLine(ox, 0, ox, oy * CIRCLE_DIAMETER_FACTOR);
	    		g2.drawLine(0, oy, ox * CIRCLE_DIAMETER_FACTOR, oy);
	    		g2.setColor(Color.BLACK);
	    		g2.setStroke(new BasicStroke(HOLE_STROKE_WIDTH));
	    		for (var h: model.getHoles()) {
	                fillCircle(g2, h.center(), h.radius());
	    		}
	    		
			    g2.setStroke(new BasicStroke(SMALL_BALL_STROKE_WIDTH));
	    		for (var b: balls) {
	                drawCircle(g2, b.pos(), b.radius());
	    		}
		
			    g2.setStroke(new BasicStroke(PLAYER_BALL_STROKE_WIDTH));
	    		var pb = model.getPlayerBall();
	    		if (pb != null) {
	                drawCircle(g2, pb.pos(), pb.radius());
	    		}
			    
			    g2.setStroke(new BasicStroke(AXIS_STROKE_WIDTH));
	    		g2.drawString(SMALL_BALL_COUNT_LABEL + balls.size(), HUD_X, HUD_BALL_COUNT_Y);
	    		g2.drawString(FPS_LABEL + model.getFramePerSec(), HUD_X, HUD_FPS_Y);
        	} finally {
	    		sync.notifyFrameRendered(frame);
        	}
        }

        private void drawCircle(Graphics2D g2, P2d center, double radius) {
            var circle = toScreenCircle(center, radius);
            g2.drawOval(circle.x(), circle.y(), circle.diameter(), circle.diameter());
        }

        private void fillCircle(Graphics2D g2, P2d center, double radius) {
            var circle = toScreenCircle(center, radius);
            g2.fillOval(circle.x(), circle.y(), circle.diameter(), circle.diameter());
        }

        private ScreenCircle toScreenCircle(P2d center, double radius) {
            int screenX = (int) (ox + center.x() * delta);
            int screenY = (int) (oy - center.y() * delta);
            int screenRadius = (int) (radius * delta);
            int diameter = screenRadius * CIRCLE_DIAMETER_FACTOR;
            return new ScreenCircle(screenX - screenRadius, screenY - screenRadius, diameter);
        }
        
    }

    private record ScreenCircle(int x, int y, int diameter) {}
}
