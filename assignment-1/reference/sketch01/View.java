package pcd.sketch01;


public class View {

	private ViewFrame frame;
	private ViewModel viewModel;
	
	public View(ViewModel model, int w, int h) {
		frame = new ViewFrame(model, w, h);	
		frame.setVisible(true);
		this.viewModel = model;
	}
		
	public void render() {
		frame.render();
	}
	
	public ViewModel getViewModel() {
		return viewModel;
	}
}
