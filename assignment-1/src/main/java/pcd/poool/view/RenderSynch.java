package pcd.poool.view;

/**
 * Monitor used to coordinate render requests with frame completion.
 * Useful when a simulation loop wants synchronous render semantics.
 */
public class RenderSynch {

	private static final long FIRST_FRAME = 0;
	private static final long NO_FRAME_RENDERED = -1;

	private long nextFrameToRender;
	private long lastFrameRendered;
	
	public RenderSynch() {
		nextFrameToRender = FIRST_FRAME;
		lastFrameRendered = NO_FRAME_RENDERED;
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
