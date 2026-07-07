package pcd.poool.verification.jpf;

/**
 * Minimal thread-based JPF harness.
 *
 * <p>The model captures one controller thread, one producer thread, and one
 * worker thread. It checks that the controller drains commands before
 * publishing the snapshot and that the worker never touches the board while
 * it is not owned by the controller.
 */
public final class ThreadedMiniHarness {

    private ThreadedMiniHarness() {
    }

    public static void main(String[] args) throws Exception {
        final MinimalProtocolState state = new MinimalProtocolState();

        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                state.submitCommand();
            }
        }, "jpf-threaded-producer");

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    state.waitForWorkAndComplete();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
            }
        }, "jpf-threaded-worker");

        Thread controller = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    state.waitUntilCommandArrives();
                    state.acquireBoardOwnership();
                    state.drainCommands();
                    state.prepareWork();
                    state.awaitWorkerAndPublish();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
            }
        }, "jpf-threaded-controller");

        producer.start();
        worker.start();
        controller.start();

        producer.join();
        worker.join();
        controller.join();

        state.assertFinalState();
    }
}
