package pcd.poool.view;

/**
 * Monitor used to coordinate render requests with frame completion.
 * Useful when a simulation loop wants synchronous render semantics.
 */
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

	public synchronized void notifyFrameRendered(long frame) {
		if (frame <= lastFrameRendered) {
			return;
		}
		lastFrameRendered = frame;
		notifyAll();
	}
	
	public synchronized void waitForFrameRendered(long frame) throws InterruptedException {
		while (lastFrameRendered < frame) {
			wait();
		}
	}
	
	
}
