package pcd.poool.verification.jpf;

/**
 * Minimal JPF model of the platform-thread protocol with two long-lived
 * workers and two independently scheduled command producers.
 */
public final class ThreadedMiniHarness {

    private ThreadedMiniHarness() {
    }

    public static void main(String[] args) throws Exception {
        final MinimalProtocolState state = new MinimalProtocolState();
        // These producers model independent user/bot command submissions.
        Thread firstProducer = producer(state, 0, "jpf-threaded-producer-0");
        Thread secondProducer = producer(state, 1, "jpf-threaded-producer-1");
        // These workers remain alive and wait for controller-assigned generations.
        Thread firstWorker = worker(state, 0, "jpf-threaded-worker-0");
        Thread secondWorker = worker(state, 1, "jpf-threaded-worker-1");
        Thread controller = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!state.allCommandsExecuted()) {
                        // The controller protocol mirrors GameLoop: wait, drain, execute, publish.
                        state.waitForPendingCommand();
                        state.beginTickAndDrainCommands();
                        state.awaitWorkersAndPublish();
                    }
                    // No more ticks are needed: release workers blocked in awaitNextGeneration.
                    state.stopWorkers();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
            }
        }, "jpf-threaded-controller");

        // JPF selects every relevant scheduling order of these starts and operations.
        firstProducer.start();
        secondProducer.start();
        firstWorker.start();
        secondWorker.start();
        controller.start();

        // The main thread waits for a stable state before evaluating final assertions.
        firstProducer.join();
        secondProducer.join();
        controller.join();
        firstWorker.join();
        secondWorker.join();
        state.assertFinalState();
    }

    private static Thread producer(final MinimalProtocolState state, final int commandId, String name) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                // A producer does not mutate game state; it only submits intent.
                state.submitCommand(commandId);
            }
        }, name);
    }

    private static Thread worker(final MinimalProtocolState state, final int workerId, String name) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                int completedGeneration = 0;
                try {
                    while (true) {
                        // Wait for the next phase, execute its abstract work, then signal the barrier.
                        int assignedGeneration = state.awaitNextGeneration(completedGeneration);
                        if (assignedGeneration < 0) {
                            return;
                        }
                        state.completeWorker(workerId, assignedGeneration);
                        completedGeneration = assignedGeneration;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
            }
        }, name);
    }
}
