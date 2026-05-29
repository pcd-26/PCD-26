package pcd.poool.view.board;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.*;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameOverReason;
import pcd.poool.model.game.GameStatus;
import pcd.poool.model.game.Player;
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
    private static final int SHOT_PREVIEW_STROKE_WIDTH = 3;
    private static final float[] SHOT_PROJECTION_DASH = {8.0f, 8.0f};
    private static final int HUD_X = 20;
    private static final int HUD_BALL_COUNT_Y = 40;
    private static final int HUD_FPS_Y = 60;
    private static final int HUD_SCORE_Y = 80;
    private static final int HUD_STATUS_Y = 100;
    private static final int HUD_METRICS_Y = 120;
    private static final String SMALL_BALL_COUNT_LABEL = "Num small balls: ";
    private static final String FPS_LABEL = "Frame per sec: ";
    private static final String RESTART_HINT = "Press R to start a new game";
    static final double SHOT_IMPULSE = 1.4;
    static final double MAX_MOUSE_SHOT_IMPULSE = 2.4;
    static final double MAX_MOUSE_DRAG_DISTANCE = 0.9;
    private static final double MIN_MOUSE_SHOT_IMPULSE = 0.05;
    private static final int SHOT_COMBO_WINDOW_MILLIS = 80;
    private static final int UP_DIRECTION = 1;
    private static final int DOWN_DIRECTION = 2;
    private static final int LEFT_DIRECTION = 4;
    private static final int RIGHT_DIRECTION = 8;
    private static final int CIRCLE_DIAMETER_FACTOR = 2;
    private static final int OVERLAY_TITLE_SIZE = 44;
    private static final int OVERLAY_HINT_SIZE = 18;
    
    private VisualiserPanel panel;
    private ViewModel model;
    private RenderSynch sync;
    private int pressedShotDirections;
    private Timer shotTimer;
    private boolean humanDragActive;
    
    public ViewFrame(ViewModel model, int w, int h){
        this(model, w, h, null, null);
    }

    public ViewFrame(ViewModel model, int w, int h, Consumer<V2d> shotHandler){
        this(model, w, h, shotHandler, null);
    }

    public ViewFrame(ViewModel model, int w, int h, Consumer<V2d> shotHandler, Runnable restartHandler){
        this(model, w, h, shotHandler, restartHandler, null);
    }

    public ViewFrame(
            ViewModel model,
            int w,
            int h,
            Consumer<V2d> shotHandler,
            Runnable restartHandler,
            Consumer<Boolean> humanAimingHandler){
    	this.model = model;
    	this.sync = new RenderSynch();
    	setTitle(WINDOW_TITLE);
        setSize(w, h + WINDOW_DECORATION_HEIGHT);
        setResizable(false);
        panel = new VisualiserPanel(w,h);
        getContentPane().add(panel);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        installInput(shotHandler, restartHandler, humanAimingHandler);
    }

    private void installInput(
            Consumer<V2d> shotHandler,
            Runnable restartHandler,
            Consumer<Boolean> humanAimingHandler) {
        shotTimer = new Timer(SHOT_COMBO_WINDOW_MILLIS, event -> fireShot(shotHandler));
        shotTimer.setRepeats(false);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_R && restartHandler != null) {
                    restartHandler.run();
                    event.consume();
                    return;
                }
                if (shotHandler == null) {
                    return;
                }
                if (isBotAiming(model)) {
                    return;
                }
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
            public void mousePressed(MouseEvent event) {
                if (shotHandler == null) {
                    return;
                }
                if (isBotAiming(model)) {
                    humanDragActive = false;
                    return;
                }
                humanDragActive = true;
                notifyHumanAiming(humanAimingHandler, true);
                updateMousePreview(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (shotHandler == null) {
                    return;
                }
                if (!humanDragActive) {
                    return;
                }
                fireMouseShotOnRelease(event, shotHandler);
                model.clearShotPreview();
                humanDragActive = false;
                notifyHumanAiming(humanAimingHandler, false);
                panel.repaint();
            }
        });
        panel.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                if (shotHandler == null) {
                    return;
                }
                if (!humanDragActive) {
                    return;
                }
                updateMousePreview(event);
            }
        });
        setFocusable(true);
        setFocusTraversalKeysEnabled(false);
        SwingUtilities.invokeLater(this::requestFocusInWindow);
    }

    private void notifyHumanAiming(Consumer<Boolean> humanAimingHandler, boolean aiming) {
        if (humanAimingHandler != null) {
            humanAimingHandler.accept(aiming);
        }
    }

    static boolean isBotAiming(ViewModel viewModel) {
        var preview = viewModel.getShotPreview();
        return preview != null && preview.player() == Player.BOT;
    }

    private void updateMousePreview(MouseEvent event) {
        var player = model.getPlayerBall();
        if (player == null) {
            return;
        }
        var target = panel.toBoardPoint(event.getX(), event.getY());
        double intensity = mouseShotIntensity(player.pos(), target);
        model.setShotPreview(player.pos(), target, intensity);
        panel.repaint();
    }

    private void fireMouseShotOnRelease(MouseEvent event, Consumer<V2d> shotHandler) {
        var player = model.getPlayerBall();
        if (player == null) {
            return;
        }
        var target = panel.toBoardPoint(event.getX(), event.getY());
        var shot = mouseShotImpulse(player.pos(), target);
        if (shot.abs() >= MIN_MOUSE_SHOT_IMPULSE) {
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

    static V2d mouseShotImpulse(P2d from, P2d target) {
        return target.sub(from).getNormalized().mul(mouseShotIntensity(from, target));
    }

    static double mouseShotIntensity(P2d from, P2d target) {
        double distance = target.sub(from).abs();
        double normalized = Math.min(distance, MAX_MOUSE_DRAG_DISTANCE) / MAX_MOUSE_DRAG_DISTANCE;
        return normalized * MAX_MOUSE_SHOT_IMPULSE;
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
                drawShotPreview(g2);
                drawEndGameOverlay(g2);
        	} finally {
	    		sync.notifyFrameRendered(frame);
        	}
        }

        private void drawEndGameOverlay(Graphics2D g2) {
            var game = model.getGame();
            if (game == null || game.status() != GameStatus.FINISHED) {
                return;
            }

            g2.setColor(new Color(255, 255, 255, 220));
            g2.fillRect(0, 0, getWidth(), getHeight());
            String title = game.winner() == null ? "Draw" : game.winner() + " wins";
            g2.setColor(game.winner() == Player.BOT ? Color.RED : Color.BLUE);
            g2.setFont(getFont().deriveFont(Font.BOLD, OVERLAY_TITLE_SIZE));
            drawCentered(g2, title, getHeight() / 2 - 25);
            g2.setColor(Color.BLACK);
            g2.setFont(getFont().deriveFont(Font.PLAIN, OVERLAY_HINT_SIZE));
            drawCentered(g2, endGameDetail(game), getHeight() / 2 + 20);
            drawCentered(g2, RESTART_HINT, getHeight() / 2 + 50);
        }

        private String endGameDetail(pcd.poool.model.game.GameSnapshot game) {
            if (game.gameOverReason() == GameOverReason.HUMAN_CUE_BALL_POCKETED) {
                return "Human cue ball was pocketed";
            }
            if (game.gameOverReason() == GameOverReason.BOT_CUE_BALL_POCKETED) {
                return "Bot cue ball was pocketed";
            }
            return "Final score  Human " + game.humanScore() + " - Bot " + game.botScore();
        }

        private void drawCentered(Graphics2D g2, String text, int y) {
            var metrics = g2.getFontMetrics();
            int x = (getWidth() - metrics.stringWidth(text)) / 2;
            g2.drawString(text, x, y);
        }

        private void drawShotPreview(Graphics2D g2) {
            var preview = model.getShotPreview();
            if (preview == null) {
                return;
            }
            var start = toScreenPoint(preview.from());
            var end = toScreenPoint(preview.to());
            g2.setColor(preview.player() == Player.BOT ? Color.RED : new Color(30, 90, 210));
            g2.setStroke(new BasicStroke(SHOT_PREVIEW_STROKE_WIDTH));
            g2.draw(new Line2D.Double(start.x(), start.y(), end.x(), end.y()));
            drawShotProjection(g2, start, end);
            drawArrowHead(g2, start, end);
            g2.setStroke(new BasicStroke(AXIS_STROKE_WIDTH));
            g2.drawString(String.format("Power: %.0f%%",
                    100 * preview.intensity() / MAX_MOUSE_SHOT_IMPULSE),
                    end.x() + 8, end.y() - 8);
        }

        private void drawShotProjection(Graphics2D g2, ScreenPoint start, ScreenPoint end) {
            var projectionEnd = projectedToPanelEdge(start, end);
            if (projectionEnd.equals(end)) {
                return;
            }
            g2.setStroke(new BasicStroke(
                    SHOT_PREVIEW_STROKE_WIDTH,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL,
                    0,
                    SHOT_PROJECTION_DASH,
                    0));
            g2.draw(new Line2D.Double(end.x(), end.y(), projectionEnd.x(), projectionEnd.y()));
            g2.setStroke(new BasicStroke(SHOT_PREVIEW_STROKE_WIDTH));
        }

        private ScreenPoint projectedToPanelEdge(ScreenPoint start, ScreenPoint end) {
            double dx = end.x() - start.x();
            double dy = end.y() - start.y();
            if (dx == 0 && dy == 0) {
                return end;
            }

            double t = Double.POSITIVE_INFINITY;
            if (dx > 0) {
                t = Math.min(t, (getWidth() - end.x()) / dx);
            } else if (dx < 0) {
                t = Math.min(t, -end.x() / dx);
            }
            if (dy > 0) {
                t = Math.min(t, (getHeight() - end.y()) / dy);
            } else if (dy < 0) {
                t = Math.min(t, -end.y() / dy);
            }
            if (!Double.isFinite(t) || t <= 0) {
                return end;
            }
            return new ScreenPoint((int) (end.x() + dx * t), (int) (end.y() + dy * t));
        }

        private void drawArrowHead(Graphics2D g2, ScreenPoint start, ScreenPoint end) {
            double angle = Math.atan2(end.y() - start.y(), end.x() - start.x());
            int arrowLength = 14;
            double leftAngle = angle + Math.PI * 0.8;
            double rightAngle = angle - Math.PI * 0.8;
            g2.draw(new Line2D.Double(
                    end.x(),
                    end.y(),
                    end.x() + Math.cos(leftAngle) * arrowLength,
                    end.y() + Math.sin(leftAngle) * arrowLength));
            g2.draw(new Line2D.Double(
                    end.x(),
                    end.y(),
                    end.x() + Math.cos(rightAngle) * arrowLength,
                    end.y() + Math.sin(rightAngle) * arrowLength));
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
            var game = model.getGame();
            return "Human ready: " + yesNo(game.humanCanShoot())
                    + "  Bot ready: " + yesNo(game.botCanShoot())
                    + "  " + status;
        }

        private String yesNo(boolean value) {
            return value ? "yes" : "no";
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
            var point = toScreenPoint(center);
            int screenRadius = (int) (radius * delta);
            int diameter = screenRadius * CIRCLE_DIAMETER_FACTOR;
            return new ScreenCircle(point.x() - screenRadius, point.y() - screenRadius, diameter);
        }

        private ScreenPoint toScreenPoint(P2d point) {
            return new ScreenPoint((int) (ox + point.x() * delta), (int) (oy - point.y() * delta));
        }

        private P2d toBoardPoint(int screenX, int screenY) {
            return new P2d((screenX - ox) / (double) delta, (oy - screenY) / (double) delta);
        }
        
    }

    private record ScreenCircle(int x, int y, int diameter) {}
    private record ScreenPoint(int x, int y) {}
}
