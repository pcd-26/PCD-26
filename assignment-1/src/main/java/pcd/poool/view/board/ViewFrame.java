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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.*;
import pcd.poool.model.common.math.P2d;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameOverReason;
import pcd.poool.model.game.GameStatus;
import pcd.poool.model.game.Player;
import pcd.poool.view.RenderSynch;

/**
 * Swing frame responsible for rendering the board and translating user input
 * into shot commands.
 *
 * <p>Mouse input uses press-drag-release semantics: press starts aiming, drag
 * updates the preview, and release submits the shot. Keyboard arrows use a
 * short combination window so diagonal input can be shown and fired as a single
 * shot. The frame does not mutate the game directly; accepted shots are sent to
 * the handler supplied by the runner.
 */
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
    private static final double KEYBOARD_PREVIEW_SCALE = 0.35;
    private static final double MIN_MOUSE_SHOT_IMPULSE = 0.05;
    private static final int SHOT_COMBO_WINDOW_MILLIS = 80;
    private static final int UP_DIRECTION = 1;
    private static final int DOWN_DIRECTION = 2;
    private static final int LEFT_DIRECTION = 4;
    private static final int RIGHT_DIRECTION = 8;
    private static final int CIRCLE_DIAMETER_FACTOR = 2;
    private static final int OVERLAY_TITLE_SIZE = 44;
    private static final int OVERLAY_HINT_SIZE = 18;
    
    /** Panel responsible for actual Swing painting. */
    private VisualiserPanel panel;
    /** Shared render state read by the EDT. */
    private ViewModel model;
    /** Monitor used to wait for frame completion. */
    private RenderSynch sync;
    /** Bit mask of arrow directions currently composing a keyboard shot. */
    private int pressedShotDirections;
    /** Timer that groups quick arrow-key combinations into one shot. */
    private Timer shotTimer;
    /** Whether a mouse drag aiming interaction is active. */
    private boolean humanDragActive;
    /** Whether a keyboard aiming interaction is active. */
    private boolean humanKeyboardActive;
    
    /**
     * Creates a read-only frame.
     *
     * @param model model rendered by the frame
     * @param w board width in pixels
     * @param h board height in pixels
     */
    public ViewFrame(ViewModel model, int w, int h){
        this(model, w, h, null, null);
    }

    /**
     * Creates a frame with shot input support.
     *
     * @param model model rendered by the frame
     * @param w board width in pixels
     * @param h board height in pixels
     * @param shotHandler callback receiving shot velocity requests
     */
    public ViewFrame(ViewModel model, int w, int h, Consumer<V2d> shotHandler){
        this(model, w, h, shotHandler, null);
    }

    /**
     * Creates a frame with shot and restart input support.
     *
     * @param model model rendered by the frame
     * @param w board width in pixels
     * @param h board height in pixels
     * @param shotHandler callback receiving shot velocity requests
     * @param restartHandler callback invoked when restart is requested
     */
    public ViewFrame(ViewModel model, int w, int h, Consumer<V2d> shotHandler, Runnable restartHandler){
        this(model, w, h, shotHandler, restartHandler, null, null);
    }

    /**
     * Creates a fully interactive frame.
     *
     * @param model model rendered by the frame
     * @param w board width in pixels
     * @param h board height in pixels
     * @param shotHandler callback receiving shot velocity requests
     * @param restartHandler callback invoked when restart is requested
     * @param humanAimingStartHandler callback used to authorize human aiming
     * @param humanAimingStopHandler callback invoked when human aiming ends
     */
    public ViewFrame(
            ViewModel model,
            int w,
            int h,
            Consumer<V2d> shotHandler,
            Runnable restartHandler,
            BooleanSupplier humanAimingStartHandler,
            Runnable humanAimingStopHandler){
    	this.model = model;
    	this.sync = new RenderSynch();
    	setTitle(WINDOW_TITLE);
        setSize(w, h + WINDOW_DECORATION_HEIGHT);
        setResizable(false);
        panel = new VisualiserPanel(w,h);
        getContentPane().add(panel);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        installInput(shotHandler, restartHandler, humanAimingStartHandler, humanAimingStopHandler);
    }

    private void installInput(
            Consumer<V2d> shotHandler,
            Runnable restartHandler,
            BooleanSupplier humanAimingStartHandler,
            Runnable humanAimingStopHandler) {
        shotTimer = new Timer(SHOT_COMBO_WINDOW_MILLIS, event -> fireShot(shotHandler, humanAimingStopHandler));
        shotTimer.setRepeats(false);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_R && restartHandler != null) {
                    resetInputState(humanAimingStopHandler);
                    restartHandler.run();
                    event.consume();
                    return;
                }
                if (shotHandler == null) {
                    return;
                }
                int direction = directionFor(event.getKeyCode());
                if (direction == 0) {
                    return;
                }
                if (!humanKeyboardActive && !tryStartHumanAiming(humanAimingStartHandler)) {
                    return;
                }
                humanKeyboardActive = true;
                pressedShotDirections |= direction;
                updateKeyboardPreview();
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
                if (!tryStartHumanAiming(humanAimingStartHandler)) {
                    humanDragActive = false;
                    return;
                }
                humanDragActive = true;
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
                model.clearShotPreview(Player.HUMAN);
                humanDragActive = false;
                stopHumanAiming(humanAimingStopHandler);
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

    private boolean tryStartHumanAiming(BooleanSupplier humanAimingStartHandler) {
        return humanAimingStartHandler == null || humanAimingStartHandler.getAsBoolean();
    }

    private void stopHumanAiming(Runnable humanAimingStopHandler) {
        if (humanAimingStopHandler != null) {
            humanAimingStopHandler.run();
        }
    }

    /**
     * @return whether the current preview is owned by the bot
     */
    static boolean isBotAiming(ViewModel viewModel) {
        var preview = viewModel.getShotPreview(Player.BOT);
        return preview != null && preview.player() == Player.BOT;
    }

    private void updateKeyboardPreview() {
        var player = model.getPlayerBall();
        if (player == null) {
            return;
        }
        var shot = keyboardShotImpulse(pressedShotDirections);
        if (shot.abs() == 0) {
            return;
        }
        model.setShotPreview(
                player.pos(),
                player.pos().sum(shot.mul(KEYBOARD_PREVIEW_SCALE)),
                shot.abs(),
                Player.HUMAN);
        panel.repaint();
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

    private void fireShot(Consumer<V2d> shotHandler, Runnable humanAimingStopHandler) {
        var shot = keyboardShotImpulse(pressedShotDirections);
        pressedShotDirections = 0;
        if (shot.abs() > 0) {
            shotHandler.accept(shot);
        }
        humanKeyboardActive = false;
        stopHumanAiming(humanAimingStopHandler);
        model.clearShotPreview(Player.HUMAN);
        panel.repaint();
    }

    private void resetInputState(Runnable humanAimingStopHandler) {
        shotTimer.stop();
        pressedShotDirections = 0;
        humanDragActive = false;
        humanKeyboardActive = false;
        stopHumanAiming(humanAimingStopHandler);
        model.clearShotPreview(Player.HUMAN);
        panel.repaint();
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

    /**
     * Converts currently pressed arrow directions into a fixed-magnitude shot.
     */
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

    /**
     * Converts the internal keyboard direction bit mask into a shot vector.
     */
    static V2d keyboardShotImpulse(int directions) {
        return shotImpulseFor(
                (directions & UP_DIRECTION) != 0,
                (directions & DOWN_DIRECTION) != 0,
                (directions & LEFT_DIRECTION) != 0,
                (directions & RIGHT_DIRECTION) != 0);
    }

    /**
     * Creates a fixed-strength impulse toward a target point.
     */
    static V2d shotImpulseToward(P2d from, P2d target) {
        return target.sub(from).getNormalized().mul(SHOT_IMPULSE);
    }

    /**
     * Creates a mouse shot whose strength depends on drag distance.
     */
    static V2d mouseShotImpulse(P2d from, P2d target) {
        return target.sub(from).getNormalized().mul(mouseShotIntensity(from, target));
    }

    /**
     * Maps mouse drag distance to shot strength, capped at the configured
     * maximum so very long drags remain controllable.
     */
    static double mouseShotIntensity(P2d from, P2d target) {
        double distance = target.sub(from).abs();
        double normalized = Math.min(distance, MAX_MOUSE_DRAG_DISTANCE) / MAX_MOUSE_DRAG_DISTANCE;
        return normalized * MAX_MOUSE_SHOT_IMPULSE;
    }

    /**
     * Requests a repaint and waits until the corresponding frame is rendered.
     */
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
        
    /**
     * Panel that converts board coordinates to screen coordinates and paints
     * the current view model.
     */
    public class VisualiserPanel extends JPanel {
        /** Screen-space X origin of the board. */
        private int ox;
        /** Screen-space Y origin of the board. */
        private int oy;
        /** Board-to-screen scale factor. */
        private int delta;
        /** Frame id to notify after the next paint operation. */
        private volatile long frameToNotify = NO_FRAME_TO_NOTIFY;
        
        /**
         * Creates the visualiser panel.
         *
         * @param w panel width in pixels
         * @param h panel height in pixels
         */
        public VisualiserPanel(int w, int h){
            setSize(w, h + WINDOW_DECORATION_HEIGHT);
            ox = w / CIRCLE_DIAMETER_FACTOR;
            oy = h / CIRCLE_DIAMETER_FACTOR;
            delta = Math.min(ox, oy);
        }

        /**
         * Associates the next paint operation with a render synchronization
         * frame id.
         *
         * @param frame frame id to signal when painting completes
         */
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
            return "Final score: Human " + game.humanScore() + " - Bot " + game.botScore();
        }

        private void drawCentered(Graphics2D g2, String text, int y) {
            var metrics = g2.getFontMetrics();
            int x = (getWidth() - metrics.stringWidth(text)) / 2;
            g2.drawString(text, x, y);
        }

        private void drawShotPreview(Graphics2D g2) {
            for (var preview : model.getShotPreviews()) {
                drawShotPreview(g2, preview);
            }
        }

        private void drawShotPreview(Graphics2D g2, ViewModel.ShotPreviewInfo preview) {
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
