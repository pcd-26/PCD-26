package pcd.poool.view.board;

import java.lang.reflect.InvocationTargetException;
import javax.swing.SwingUtilities;

public class View {

	private ViewFrame frame;
	private ViewModel viewModel;
	
	public View(ViewModel model, int w, int h) {
		this.viewModel = model;
		displayFrame(model, w, h);
	}

	private void displayFrame(ViewModel model, int w, int h) {
		if (SwingUtilities.isEventDispatchThread()) {
			createAndShowFrame(model, w, h);
			return;
		}
		try {
			SwingUtilities.invokeAndWait(() -> createAndShowFrame(model, w, h));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while showing view", ex);
		} catch (InvocationTargetException ex) {
			throw new IllegalStateException("Could not show view", ex.getCause());
		}
	}

	private void createAndShowFrame(ViewModel model, int w, int h) {
		frame = new ViewFrame(model, w, h);	
		frame.setVisible(true);
	}
		
	public void render() {
		frame.render();
	}
	
	public ViewModel getViewModel() {
		return viewModel;
	}
}
