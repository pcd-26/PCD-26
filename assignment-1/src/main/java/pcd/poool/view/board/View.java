package pcd.poool.view.board;

import java.lang.reflect.InvocationTargetException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import pcd.poool.model.common.math.V2d;

/**
 * Swing view facade used by game launchers.
 *
 * <p>The facade creates the frame on the Swing EDT and exposes a small render
 * method so simulation loops do not manipulate the frame directly.
 */
public class View {

	private ViewFrame frame;
	private ViewModel viewModel;
	
	/**
	 * Creates a passive view without input callbacks.
	 *
	 * @param model shared view model
	 * @param w window width in pixels
	 * @param h window height in pixels
	 */
	public View(ViewModel model, int w, int h) {
		this.viewModel = model;
		displayFrame(model, w, h, null, null, null, null);
	}

	/**
	 * Creates a view with shot input support.
	 *
	 * @param model shared view model
	 * @param w window width in pixels
	 * @param h window height in pixels
	 * @param shotHandler callback receiving shot velocity requests
	 */
	public View(ViewModel model, int w, int h, Consumer<V2d> shotHandler) {
		this.viewModel = model;
		displayFrame(model, w, h, shotHandler, null, null, null);
	}

	/**
	 * Creates a view with shot and restart input support.
	 *
	 * @param model shared view model
	 * @param w window width in pixels
	 * @param h window height in pixels
	 * @param shotHandler callback receiving shot velocity requests
	 * @param restartHandler callback invoked when restart is requested
	 */
	public View(ViewModel model, int w, int h, Consumer<V2d> shotHandler, Runnable restartHandler) {
		this.viewModel = model;
		displayFrame(model, w, h, shotHandler, restartHandler, null, null);
	}

	/**
	 * Creates a full interactive view.
	 *
	 * @param model shared view model
	 * @param w window width in pixels
	 * @param h window height in pixels
	 * @param shotHandler callback receiving shot velocity requests
	 * @param restartHandler callback invoked when restart is requested
	 * @param humanAimingStartHandler callback used to authorize human aiming
	 * @param humanAimingStopHandler callback invoked when human aiming ends
	 */
	public View(
			ViewModel model,
			int w,
			int h,
			Consumer<V2d> shotHandler,
			Runnable restartHandler,
			BooleanSupplier humanAimingStartHandler,
			Runnable humanAimingStopHandler) {
		this.viewModel = model;
		displayFrame(model, w, h, shotHandler, restartHandler, humanAimingStartHandler, humanAimingStopHandler);
	}

	private void displayFrame(
			ViewModel model,
			int w,
			int h,
			Consumer<V2d> shotHandler,
			Runnable restartHandler,
			BooleanSupplier humanAimingStartHandler,
			Runnable humanAimingStopHandler) {
		if (SwingUtilities.isEventDispatchThread()) {
			createAndShowFrame(model, w, h, shotHandler, restartHandler, humanAimingStartHandler, humanAimingStopHandler);
			return;
		}
		try {
			SwingUtilities.invokeAndWait(() -> createAndShowFrame(
					model,
					w,
					h,
					shotHandler,
					restartHandler,
					humanAimingStartHandler,
					humanAimingStopHandler));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while showing view", ex);
		} catch (InvocationTargetException ex) {
			throw new IllegalStateException("Could not show view", ex.getCause());
		}
	}

	private void createAndShowFrame(
			ViewModel model,
			int w,
			int h,
			Consumer<V2d> shotHandler,
			Runnable restartHandler,
			BooleanSupplier humanAimingStartHandler,
			Runnable humanAimingStopHandler) {
		frame = new ViewFrame(
				model,
				w,
				h,
				shotHandler,
				restartHandler,
				humanAimingStartHandler,
				humanAimingStopHandler);	
		frame.setVisible(true);
	}
		
	/**
	 * Requests rendering of the current view model.
	 */
    public void render() {
        frame.render();
    }

    /**
     * Disposes the Swing window created by this view.
     */
    public void close() {
        frame.close();
    }
	
	/**
	 * Gets the model rendered by this view.
	 *
	 * @return shared view model rendered by this view
	 */
	public ViewModel getViewModel() {
		return viewModel;
	}
}
