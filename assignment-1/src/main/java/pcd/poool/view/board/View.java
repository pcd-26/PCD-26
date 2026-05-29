package pcd.poool.view.board;

import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import pcd.poool.model.common.math.V2d;

public class View {

	private ViewFrame frame;
	private ViewModel viewModel;
	
	public View(ViewModel model, int w, int h) {
		this.viewModel = model;
		displayFrame(model, w, h, null, null, null);
	}

	public View(ViewModel model, int w, int h, Consumer<V2d> shotHandler) {
		this.viewModel = model;
		displayFrame(model, w, h, shotHandler, null, null);
	}

	public View(ViewModel model, int w, int h, Consumer<V2d> shotHandler, Runnable restartHandler) {
		this.viewModel = model;
		displayFrame(model, w, h, shotHandler, restartHandler, null);
	}

	public View(
			ViewModel model,
			int w,
			int h,
			Consumer<V2d> shotHandler,
			Runnable restartHandler,
			Consumer<Boolean> humanAimingHandler) {
		this.viewModel = model;
		displayFrame(model, w, h, shotHandler, restartHandler, humanAimingHandler);
	}

	private void displayFrame(
			ViewModel model,
			int w,
			int h,
			Consumer<V2d> shotHandler,
			Runnable restartHandler,
			Consumer<Boolean> humanAimingHandler) {
		if (SwingUtilities.isEventDispatchThread()) {
			createAndShowFrame(model, w, h, shotHandler, restartHandler, humanAimingHandler);
			return;
		}
		try {
			SwingUtilities.invokeAndWait(() -> createAndShowFrame(
					model,
					w,
					h,
					shotHandler,
					restartHandler,
					humanAimingHandler));
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
			Consumer<Boolean> humanAimingHandler) {
		frame = new ViewFrame(model, w, h, shotHandler, restartHandler, humanAimingHandler);	
		frame.setVisible(true);
	}
		
	public void render() {
		frame.render();
	}
	
	public ViewModel getViewModel() {
		return viewModel;
	}
}
