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
    private static final float MIN_ARROW_WIDTH = 2.0f;
    private static final float MAX_ARROW_WIDTH = 5.0f;
    private static final float SHOT_PROJECTION_STROKE_WIDTH = 2.0f;
    private static final float[] SHOT_PROJECTION_DASH = {8.0f, 8.0f};
    private static final int HUD_X = 20;
    private static final int HUD_STATS_X = 20;
    private static final int HUD_BALL_COUNT_Y = 40;
    private static final int HUD_FPS_Y = 90;
    private static final int HUD_HUMAN_READY_Y = 110;
    private static final int HUD_BOT_READY_Y = 130;
    private static final int HUD_STATUS_Y = 150;
    private static final int HUD_METRICS_Y = 170;
    private static final String SMALL_BALL_COUNT_LABEL = "Balls: ";
    private static final String FPS_LABEL = "FPS: ";
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
    /** System time when the game started. */
    private long gameStartSystemTime = System.currentTimeMillis();
    /** Button to restart the game. */
    private JButton newGameButton;

    private void resetCountdown() {
        gameStartSystemTime = System.currentTimeMillis();
    }
    
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
        
        // Setup New Game button with modern custom UI styling
        newGameButton = new JButton("New Game") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                var game = ViewFrame.this.model.getGame();
                boolean isBotWinner = (game != null && game.winner() == Player.BOT);
                
                Color bg;
                if (isBotWinner) {
                    if (getModel().isPressed()) {
                        bg = new Color(175, 25, 25); // Darker red when pressed
                    } else if (getModel().isRollover()) {
                        bg = new Color(240, 40, 40); // Lighter vibrant red on hover
                    } else {
                        bg = new Color(210, 30, 30); // Standard premium red
                    }
                } else {
                    if (getModel().isPressed()) {
                        bg = new Color(25, 75, 175); // Darker blue when pressed
                    } else if (getModel().isRollover()) {
                        bg = new Color(40, 120, 240); // Lighter vibrant blue on hover
                    } else {
                        bg = new Color(30, 90, 210); // Standard premium blue
                    }
                }
                
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                var fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(getText())) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(getText(), x, y);
                
                g2.dispose();
            }
        };
        newGameButton.setContentAreaFilled(false);
        newGameButton.setBorderPainted(false);
        newGameButton.setFocusPainted(false);
        newGameButton.setRolloverEnabled(true);
        newGameButton.setFont(newGameButton.getFont().deriveFont(Font.BOLD, 16f));
        newGameButton.setBounds(w / 2 - 75, (h + WINDOW_DECORATION_HEIGHT) / 2 + 85, 150, 40);
        newGameButton.setFocusable(false);
        newGameButton.setVisible(false);
        newGameButton.addActionListener(e -> {
            resetInputState(humanAimingStopHandler);
            resetCountdown();
            if (restartHandler != null) {
                restartHandler.run();
            }
        });
        panel.setLayout(null);
        panel.add(newGameButton);
        
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
                    resetCountdown();
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
        if (System.currentTimeMillis() - gameStartSystemTime < 3000) {
            return false;
        }
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
     * Calculates the shot preview stroke width based on the launch force intensity.
     * The width spans from MIN_ARROW_WIDTH (2.0f) to MAX_ARROW_WIDTH (5.0f) when going from 0% to 100% force.
     *
     * @param intensity the current launch force intensity
     * @return the scaled stroke width
     */
    static float calculateShotPreviewWidth(double intensity) {
        double ratio = Math.max(0.0, Math.min(1.0, intensity / MAX_MOUSE_SHOT_IMPULSE));
        return (float) (MIN_ARROW_WIDTH + (MAX_ARROW_WIDTH - MIN_ARROW_WIDTH) * ratio);
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
     * Disposes the underlying Swing window.
     */
    public void close() {
        dispose();
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
                g2.setColor(new Color(30, 90, 210));
	    		var pb = model.getPlayerBall();
	    		if (pb != null) {
	                drawCircle(g2, pb.pos(), pb.radius());
	    		}

                g2.setStroke(new BasicStroke(BOT_BALL_STROKE_WIDTH));
                g2.setColor(new Color(220, 30, 30));
                var bot = model.getBotBall();
                if (bot != null) {
                    drawCircle(g2, bot.pos(), bot.radius());
                }
			    
                g2.setColor(Color.BLACK);
			    g2.setStroke(new BasicStroke(AXIS_STROKE_WIDTH));
                Font oldFont = g2.getFont();
                g2.setFont(oldFont.deriveFont(Font.BOLD, 18f));
                String ballCountText = SMALL_BALL_COUNT_LABEL + balls.size();
                var metrics = g2.getFontMetrics();
                int centerCountX = (getWidth() - metrics.stringWidth(ballCountText)) / 2;
                g2.drawString(ballCountText, centerCountX, HUD_BALL_COUNT_Y);
                g2.setFont(oldFont);
	    		g2.drawString(FPS_LABEL + model.getFramePerSec(), HUD_STATS_X, HUD_FPS_Y);
                drawGameHud(g2);
                drawShotPreview(g2);
                drawCountdownOverlay(g2);
                drawEndGameOverlay(g2);
                
                // Update New Game button visibility safely on EDT
                var game = model.getGame();
                boolean finished = (game != null && game.status() == GameStatus.FINISHED);
                if (newGameButton != null && newGameButton.isVisible() != finished) {
                    SwingUtilities.invokeLater(() -> newGameButton.setVisible(finished));
                }
        	} finally {
	    		sync.notifyFrameRendered(frame);
        	}
        }

        private void drawCountdownOverlay(Graphics2D g2) {
            long elapsed = System.currentTimeMillis() - gameStartSystemTime;
            if (elapsed >= 4000) {
                return;
            }

            String text;
            if (elapsed < 1000) {
                text = "3";
            } else if (elapsed < 2000) {
                text = "2";
            } else if (elapsed < 3000) {
                text = "1";
            } else {
                text = "GO!";
            }

            Font oldFont = g2.getFont();
            Color oldColor = g2.getColor();

            g2.setFont(oldFont.deriveFont(Font.BOLD, 72f));
            if (text.equals("GO!")) {
                g2.setColor(new Color(40, 180, 90)); // Soft green
            } else {
                g2.setColor(new Color(230, 80, 80)); // Soft red/orange
            }

            var metrics = g2.getFontMetrics();
            int x = (getWidth() - metrics.stringWidth(text)) / 2;
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();

            g2.drawString(text, x, y);

            g2.setFont(oldFont);
            g2.setColor(oldColor);
        }

        private void drawEndGameOverlay(Graphics2D g2) {
            var game = model.getGame();
            if (game == null || game.status() != GameStatus.FINISHED) {
                return;
            }

            g2.setColor(new Color(255, 255, 255, 220));
            g2.fillRect(0, 0, getWidth(), getHeight());
            String title = game.winner() == null ? "Draw" : game.winner() + " wins";
            g2.setColor(game.winner() == Player.BOT ? new Color(220, 30, 30) : new Color(30, 90, 210));
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
            g2.setColor(preview.player() == Player.BOT ? new Color(220, 30, 30) : new Color(30, 90, 210));
            float scaledWidth = calculateShotPreviewWidth(preview.intensity());
            
            // Draw the arrow vector (solid segment and arrowhead) with scaled width
            g2.setStroke(new BasicStroke(scaledWidth));
            
            double dx = end.x() - start.x();
            double dy = end.y() - start.y();
            double distance = Math.hypot(dx, dy);
            ScreenPoint arrowStart = start;
            if (distance > 0) {
                double radius = 0;
                if (preview.player() == Player.HUMAN) {
                    var pb = model.getPlayerBall();
                    if (pb != null) {
                        radius = pb.radius();
                    }
                } else if (preview.player() == Player.BOT) {
                    var bot = model.getBotBall();
                    if (bot != null) {
                        radius = bot.radius();
                    }
                }
                int screenRadius = (int) (radius * delta);
                if (distance > screenRadius) {
                    int offsetX = (int) Math.round((dx / distance) * screenRadius);
                    int offsetY = (int) Math.round((dy / distance) * screenRadius);
                    arrowStart = new ScreenPoint(start.x() + offsetX, start.y() + offsetY);
                } else {
                    arrowStart = end;
                }
            }
            
            g2.draw(new Line2D.Double(arrowStart.x(), arrowStart.y(), end.x(), end.y()));
            drawArrowHead(g2, start, end);
            
            // Draw the projection with the baseline width (dashed)
            drawShotProjection(g2, start, end);
            
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
                    SHOT_PROJECTION_STROKE_WIDTH,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL,
                    0,
                    SHOT_PROJECTION_DASH,
                    0));
            g2.draw(new Line2D.Double(end.x(), end.y(), projectionEnd.x(), projectionEnd.y()));
            g2.setStroke(new BasicStroke(SHOT_PROJECTION_STROKE_WIDTH));
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
            
            int[] xPoints = {
                end.x(),
                (int) Math.round(end.x() + Math.cos(leftAngle) * arrowLength),
                (int) Math.round(end.x() + Math.cos(rightAngle) * arrowLength)
            };
            int[] yPoints = {
                end.y(),
                (int) Math.round(end.y() + Math.sin(leftAngle) * arrowLength),
                (int) Math.round(end.y() + Math.sin(rightAngle) * arrowLength)
            };
            
            g2.fillPolygon(xPoints, yPoints, 3);
        }

        private void drawGameHud(Graphics2D g2) {
            var game = model.getGame();
            if (game == null) {
                return;
            }
            String humanText = game.humanCanShoot() ? "Human ready" : "Human busy";
            String botText = game.botCanShoot() ? "Bot ready" : "Bot busy";
            
            g2.drawString(humanText, HUD_STATS_X, HUD_HUMAN_READY_Y);
            g2.drawString(botText, HUD_STATS_X, HUD_BOT_READY_Y);
            g2.drawString(statusText(game.status(), game.winner()), HUD_STATS_X, HUD_STATUS_Y);
            g2.drawString(String.format("Avg step: %.4f ms", game.averageStepMillis()),
                    HUD_STATS_X, HUD_METRICS_Y);
            drawCornerScores(g2, game);
        }

        private void drawCornerScores(Graphics2D g2, pcd.poool.model.game.GameSnapshot game) {
            Font oldFont = g2.getFont();
            Color oldColor = g2.getColor();

            // Set a premium, large font for labels and scores
            Font labelFont = oldFont.deriveFont(Font.BOLD, 22f);
            Font scoreFont = oldFont.deriveFont(Font.BOLD, 36f);

            int labelY = getHeight() - 25; // Label Y (bottom)
            int scoreY = labelY - 40;      // Score Y (above label)

            // 1. Human (Left bottom corner) in blue
            g2.setFont(scoreFont);
            g2.setColor(new Color(30, 90, 210)); // Human color
            g2.drawString(String.valueOf(game.humanScore()), 25, scoreY);
            
            g2.setFont(labelFont);
            g2.drawString("Human", 25, labelY);

            // 2. Bot (Right bottom corner) in red
            g2.setFont(scoreFont);
            g2.setColor(new Color(220, 30, 30)); // Bot color
            var metrics = g2.getFontMetrics();
            int botScoreX = getWidth() - 25 - metrics.stringWidth(String.valueOf(game.botScore()));
            g2.drawString(String.valueOf(game.botScore()), botScoreX, scoreY);

            g2.setFont(labelFont);
            metrics = g2.getFontMetrics();
            int botLabelX = getWidth() - 25 - metrics.stringWidth("Bot");
            g2.drawString("Bot", botLabelX, labelY);

            // Restore
            g2.setFont(oldFont);
            g2.setColor(oldColor);
        }

        private String statusText(GameStatus status, Object winner) {
            if (status == GameStatus.FINISHED) {
                return "Game status: Finished (" + (winner == null ? "draw" : "winner: " + winner) + ")";
            }
            return "Game status: " + status;
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
