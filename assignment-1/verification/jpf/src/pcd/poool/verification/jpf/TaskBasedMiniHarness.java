package pcd.poool.verification.jpf;

/**
 * Minimal task-based JPF harness.
 *
 * <p>The model uses a short-lived task thread instead of a long-lived worker
 * thread. This keeps the task-based verification protocol distinct while
 * remaining small enough for JPF exploration.
 */
public final class TaskBasedMiniHarness {

    private TaskBasedMiniHarness() {
    }

    public static void main(String[] args) throws Exception {
        final MinimalProtocolState state = new MinimalProtocolState();

        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                state.submitCommand();
            }
        }, "jpf-task-producer");

        Thread controller = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    state.waitUntilCommandArrives();
                    state.acquireBoardOwnership();
                    state.drainCommands();
                    state.prepareWork();

                    Thread task = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                state.waitForWorkAndComplete();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(ex);
                            }
                        }
                    }, "jpf-task-worker");

                    task.start();
                    task.join();

                    state.awaitWorkerAndPublish();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
            }
        }, "jpf-task-controller");

        producer.start();
        controller.start();

        producer.join();
        controller.join();

        state.assertFinalState();
    }
}
