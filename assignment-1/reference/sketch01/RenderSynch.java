package pcd.sketch01;

public class RenderSynch {

	private long nextFrameToRender;
	private long lastFrameRendered;
	
	public RenderSynch() {
		nextFrameToRender = 0;
		lastFrameRendered = -1;
	}
	public synchronized long nextFrameToRender() {
		long f = nextFrameToRender;
		nextFrameToRender++;
		return f;
	}

	public synchronized void notifyFrameRendered() {
		lastFrameRendered++;
		notifyAll();
	}
	
	public synchronized void waitForFrameRendered(long frame) throws InterruptedException {
		while (lastFrameRendered < frame) {
			wait();
		}
	}
	
	
}
