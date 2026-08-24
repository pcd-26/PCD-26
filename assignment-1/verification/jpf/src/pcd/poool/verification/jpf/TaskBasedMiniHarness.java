package pcd.poool.verification.jpf;

/**
 * Minimal JPF model of the task-based protocol with two short-lived tasks per
 * tick and two independently scheduled command producers.
 */
public final class TaskBasedMiniHarness {

    private TaskBasedMiniHarness() {
    }

    public static void main(String[] args) throws Exception {
        final MinimalProtocolState state = new MinimalProtocolState();
        // Two producers submit commands independently, as the user and bot may do.
        Thread firstProducer = producer(state, 0, "jpf-task-producer-0");
        Thread secondProducer = producer(state, 1, "jpf-task-producer-1");
        Thread controller = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (!state.allCommandsExecuted()) {
                        // The controller drains a batch before constructing its task phase.
                        state.waitForPendingCommand();
                        int generation = state.beginTickAndDrainCommands();
                        // Each task owns one abstract work range for this generation.
                        Thread firstTask = task(state, 0, generation, "jpf-task-worker-0");
                        Thread secondTask = task(state, 1, generation, "jpf-task-worker-1");
                        firstTask.start();
                        secondTask.start();
                        // The barrier models waiting on every submitted Future before publication.
                        state.awaitWorkersAndPublish();
                        // Joining also proves that no short-lived task survives this tick.
                        firstTask.join();
                        secondTask.join();
                    }
                    state.stopWorkers();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
            }
        }, "jpf-task-controller");

        // JPF explores the possible interleavings between producers, controller, and tasks.
        firstProducer.start();
        secondProducer.start();
        controller.start();

        firstProducer.join();
        secondProducer.join();
        controller.join();
        state.assertFinalState();
    }

    private static Thread producer(final MinimalProtocolState state, final int commandId, String name) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                // Producers only enqueue commands; the controller remains the model owner.
                state.submitCommand(commandId);
            }
        }, name);
    }

    private static Thread task(
            final MinimalProtocolState state,
            final int workerId,
            final int generation,
            String name) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                // The task reports exactly one completion to the shared barrier.
                state.completeWorker(workerId, generation);
            }
        }, name);
    }
}
