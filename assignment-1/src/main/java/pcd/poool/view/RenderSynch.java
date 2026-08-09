package pcd.poool.view;

/**
 * Synchronization helper between the runtime thread and the Swing EDT.
 *
 * <p>The runtime assigns a monotonically increasing frame id before each
 * repaint and then waits until Swing confirms that the same frame has been
 * painted. This keeps rendering ordered and avoids mixing simulation steps
 * with stale or partial frames.
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
		long frameId = nextFrameToRender;
		nextFrameToRender++;
		return frameId;
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
