package pcd.poool.view.board;

import java.lang.reflect.InvocationTargetException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import pcd.poool.model.common.math.V2d;

/**
 * Swing facade for the board view.
 */
public class View {

	private ViewFrame frame;
	private ViewModel viewModel;

	public View(ViewModel model, int w, int h) {
		this.viewModel = model;
		displayFrame(model, w, h, null, null, null, null);
	}

	public View(ViewModel model, int w, int h, Consumer<V2d> shotHandler) {
		this.viewModel = model;
		displayFrame(model, w, h, shotHandler, null, null, null);
	}

	public View(ViewModel model, int w, int h, Consumer<V2d> shotHandler, Runnable restartHandler) {
		this.viewModel = model;
		displayFrame(model, w, h, shotHandler, restartHandler, null, null);
	}

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

	public void render() {
		frame.render();
	}

	public void close() {
		frame.close();
	}

	public ViewModel getViewModel() {
		return viewModel;
	}
}
