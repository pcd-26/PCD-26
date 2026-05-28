package pcd.sketch02.view;

import javax.swing.SwingUtilities;

import pcd.sketch02.controller.ActiveController;
import pcd.sketch02.controller.Cmd;
import pcd.sketch02.model.Counter;
import pcd.sketch02.model.CounterObserver;
import pcd.sketch02.util.BoundedBuffer;

public class View implements CounterObserver {

	private ViewModel viewModel;
	
	private ViewFrame frame;
	
	public View(ViewModel viewModel, ActiveController controller) {
		this.viewModel = viewModel;
		frame = new ViewFrame(viewModel, controller);	
	}

	public void display() {
		SwingUtilities.invokeLater(() -> {
			frame.setVisible(true);
		});
	}
	
	@Override
	public synchronized void modelUpdated(Counter model) {
		viewModel.update(model.getCount());
		frame.refresh();
	}
}
