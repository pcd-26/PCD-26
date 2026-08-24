package pcd.poool.model.physics.threaded;

/**
 * JPF harness that executes the production worker and completion-barrier
 * classes used by the production threaded physics engine.
 *
 * <p>It deliberately keeps the physics chunk small: the purpose is to
 * exhaustively explore the synchronization protocol, rather than numerical
 * collision computations. Two persistent workers execute two phases and the
 * coordinator commits each phase only after the real completion monitor
 * returns.</p>
 */
public final class PhysicsWorkersJpfHarness {

    private static final int WORKER_COUNT = 2;
    private static final int PHASE_COUNT = 2;

    private PhysicsWorkersJpfHarness() {
    }

    public static void main(String[] args) {
        var state = new VerificationState();

        // These are the same long-lived worker implementation owned by the
        // production threaded physics engine.
        try (var firstWorker = new PhysicsWorker("jpf-physics-worker-0");
             var secondWorker = new PhysicsWorker("jpf-physics-worker-1")) {
            for (int phase = 0; phase < PHASE_COUNT; phase++) {
                final int currentPhase = phase;
                var completion = new WorkerCompletionMonitor(WORKER_COUNT);

                // Each worker receives a separate contiguous physics chunk,
                // as happens in the movement and grid-building phases.
                firstWorker.assign(() -> state.executeChunk(0, currentPhase), completion);
                secondWorker.assign(() -> state.executeChunk(1, currentPhase), completion);

                // This call uses the real monitor. A commit before both chunks
                // complete would fail the assertions below.
                completion.await();
                state.commitPhase(currentPhase);
            }
        }

        // Closing the workers is part of the production resource lifecycle.
        state.assertFinalState();
    }

    /** Records only observable effects of a physics chunk for verification. */
    private static final class VerificationState {

        private final int[] executions = new int[WORKER_COUNT];
        private int committedPhases;

        synchronized void executeChunk(int workerId, int phase) {
            assert phase == committedPhases
                    : "a worker executed a phase that the coordinator did not open";
            assert executions[workerId] == phase
                    : "one worker executed a chunk more than once in a phase";
            executions[workerId]++;
        }

        synchronized void commitPhase(int phase) {
            assert phase == committedPhases : "phases must commit in order";
            for (int workerId = 0; workerId < WORKER_COUNT; workerId++) {
                assert executions[workerId] == phase + 1
                        : "the coordinator committed before every worker completed";
            }
            committedPhases++;
        }

        synchronized void assertFinalState() {
            assert committedPhases == PHASE_COUNT : "not every phase was committed";
            for (int workerId = 0; workerId < WORKER_COUNT; workerId++) {
                assert executions[workerId] == PHASE_COUNT
                        : "a worker did not execute exactly one chunk per phase";
            }
        }
    }
}
