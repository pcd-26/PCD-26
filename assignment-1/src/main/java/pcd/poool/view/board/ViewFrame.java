package pcd.poool.view.board;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.*;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameStatus;
import pcd.poool.view.RenderSynch;

public class ViewFrame extends JFrame {

    private static final String WINDOW_TITLE = "Poool";
    private static final int WINDOW_DECORATION_HEIGHT = 25;
    private static final long NO_FRAME_TO_NOTIFY = -1;
    private static final int AXIS_STROKE_WIDTH = 1;
    private static final int HOLE_STROKE_WIDTH = 2;
    private static final int SMALL_BALL_STROKE_WIDTH = 1;
    private static final int PLAYER_BALL_STROKE_WIDTH = 3;
    private static final int BOT_BALL_STROKE_WIDTH = 3;
    private static final int HUD_X = 20;
    private static final int HUD_BALL_COUNT_Y = 40;
    private static final int HUD_FPS_Y = 60;
    private static final int HUD_SCORE_Y = 80;
    private static final int HUD_STATUS_Y = 100;
    private static final int HUD_METRICS_Y = 120;
    private static final String SMALL_BALL_COUNT_LABEL = "Num small balls: ";
    private static final String FPS_LABEL = "Frame per sec: ";
    static final double SHOT_IMPULSE = 1.4;
    private static final int SHOT_COMBO_WINDOW_MILLIS = 80;
    private static final int UP_DIRECTION = 1;
    private static final int DOWN_DIRECTION = 2;
    private static final int LEFT_DIRECTION = 4;
    private static final int RIGHT_DIRECTION = 8;
    private static final int CIRCLE_DIAMETER_FACTOR = 2;
    
    private VisualiserPanel panel;
    private ViewModel model;
    private RenderSynch sync;
    private int pressedShotDirections;
    private Timer shotTimer;
    
    public ViewFrame(ViewModel model, int w, int h){
        this(model, w, h, null);
    }

    public ViewFrame(ViewModel model, int w, int h, Consumer<V2d> shotHandler){
    	this.model = model;
    	this.sync = new RenderSynch();
    	setTitle(WINDOW_TITLE);
        setSize(w, h + WINDOW_DECORATION_HEIGHT);
        setResizable(false);
        panel = new VisualiserPanel(w,h);
        getContentPane().add(panel);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        if (shotHandler != null) {
            installInput(shotHandler);
        }
    }

    private void installInput(Consumer<V2d> shotHandler) {
        shotTimer = new Timer(SHOT_COMBO_WINDOW_MILLIS, event -> fireShot(shotHandler));
        shotTimer.setRepeats(false);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                int direction = directionFor(event.getKeyCode());
                if (direction == 0) {
                    return;
                }
                pressedShotDirections |= direction;
                shotTimer.restart();
                event.consume();
            }
        });
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                fireMouseShot(event, shotHandler);
            }
        });
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        SwingUtilities.invokeLater(this::requestFocusInWindow);
    }

    private void fireMouseShot(MouseEvent event, Consumer<V2d> shotHandler) {
        var player = model.getPlayerBall();
        if (player == null) {
            return;
        }
        var target = panel.toBoardPoint(event.getX(), event.getY());
        var shot = shotImpulseToward(player.pos(), target);
        if (shot.abs() > 0) {
            shotHandler.accept(shot);
        }
    }

    private void fireShot(Consumer<V2d> shotHandler) {
        var shot = shotImpulseFor(
                (pressedShotDirections & UP_DIRECTION) != 0,
                (pressedShotDirections & DOWN_DIRECTION) != 0,
                (pressedShotDirections & LEFT_DIRECTION) != 0,
                (pressedShotDirections & RIGHT_DIRECTION) != 0);
        pressedShotDirections = 0;
        if (shot.abs() > 0) {
            shotHandler.accept(shot);
        }
    }

    private int directionFor(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_UP -> UP_DIRECTION;
            case KeyEvent.VK_DOWN -> DOWN_DIRECTION;
            case KeyEvent.VK_LEFT -> LEFT_DIRECTION;
            case KeyEvent.VK_RIGHT -> RIGHT_DIRECTION;
            default -> 0;
        };
    }

    static V2d shotImpulseFor(boolean up, boolean down, boolean left, boolean right) {
        double x = 0;
        double y = 0;
        if (up) {
            y += 1;
        }
        if (down) {
            y -= 1;
        }
        if (left) {
            x -= 1;
        }
        if (right) {
            x += 1;
        }
        return new V2d(x, y).getNormalized().mul(SHOT_IMPULSE);
    }

    static V2d shotImpulseToward(P2d from, P2d target) {
        return target.sub(from).getNormalized().mul(SHOT_IMPULSE);
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
                g2.setColor(Color.BLUE);
	    		var pb = model.getPlayerBall();
	    		if (pb != null) {
	                drawCircle(g2, pb.pos(), pb.radius());
	    		}

                g2.setStroke(new BasicStroke(BOT_BALL_STROKE_WIDTH));
                g2.setColor(Color.RED);
                var bot = model.getBotBall();
                if (bot != null) {
                    drawCircle(g2, bot.pos(), bot.radius());
                }
			    
                g2.setColor(Color.BLACK);
			    g2.setStroke(new BasicStroke(AXIS_STROKE_WIDTH));
	    		g2.drawString(SMALL_BALL_COUNT_LABEL + balls.size(), HUD_X, HUD_BALL_COUNT_Y);
	    		g2.drawString(FPS_LABEL + model.getFramePerSec(), HUD_X, HUD_FPS_Y);
                drawGameHud(g2);
        	} finally {
	    		sync.notifyFrameRendered(frame);
        	}
        }

        private void drawGameHud(Graphics2D g2) {
            var game = model.getGame();
            if (game == null) {
                return;
            }
            g2.drawString("Human: " + game.humanScore() + "  Bot: " + game.botScore(),
                    HUD_X, HUD_SCORE_Y);
            g2.drawString(statusText(game.status(), game.winner()), HUD_X, HUD_STATUS_Y);
            g2.drawString(String.format("Avg step: %.4f ms", game.averageStepMillis()),
                    HUD_X, HUD_METRICS_Y);
        }

        private String statusText(GameStatus status, Object winner) {
            if (status == GameStatus.FINISHED) {
                return winner == null ? "Finished: draw" : "Winner: " + winner;
            }
            return "Turn: " + model.getGame().currentPlayer() + " - " + status;
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

        private P2d toBoardPoint(int screenX, int screenY) {
            return new P2d((screenX - ox) / (double) delta, (oy - screenY) / (double) delta);
        }
        
    }

    private record ScreenCircle(int x, int y, int diameter) {}
}
