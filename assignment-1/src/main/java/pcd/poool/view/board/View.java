package pcd.poool.view.board;

import javax.swing.SwingUtilities;

public class View {

	private ViewFrame frame;
	private ViewModel viewModel;
	
	public View(ViewModel model, int w, int h) {
		frame = new ViewFrame(model, w, h);	
		SwingUtilities.invokeLater(() -> frame.setVisible(true));
		this.viewModel = model;
	}
		
	public void render() {
		frame.render();
	}
	
	public ViewModel getViewModel() {
		return viewModel;
	}
}
