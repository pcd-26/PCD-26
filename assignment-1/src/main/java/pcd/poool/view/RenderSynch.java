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
	
	/**
	 * Creates a render synchronization monitor.
	 */
	public RenderSynch() {
		nextFrameToRender = FIRST_FRAME;
		lastFrameRendered = NO_FRAME_RENDERED;
	}

	/**
	 * Reserves the next frame identifier for rendering.
	 *
	 * @return monotonically increasing frame id
	 */
	public synchronized long nextFrameToRender() {
		long f = nextFrameToRender;
		nextFrameToRender++;
		return f;
	}

	/**
	 * Marks a frame as rendered and wakes waiting simulation threads.
	 *
	 * @param frame completed frame id
	 */
	public synchronized void notifyFrameRendered(long frame) {
		if (frame <= lastFrameRendered) {
			return;
		}
		lastFrameRendered = frame;
		notifyAll();
	}
	
	/**
	 * Waits until the requested frame has been rendered.
	 *
	 * @param frame frame id to wait for
	 * @throws InterruptedException if interrupted while waiting
	 */
	public synchronized void waitForFrameRendered(long frame) throws InterruptedException {
		while (lastFrameRendered < frame) {
			wait();
		}
	}
	
	
}
