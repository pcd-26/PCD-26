package pcd.poool.model.physics.taskbased;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JPF model of the task-based physics phase: submit all independent chunks,
 * wait for the batch barrier, then commit the phase.
 *
 * <p>This class belongs only to the verification area. It recreates the
 * scheduling boundary of {@code TaskBasedPhysicsEngine} without changing the
 * production executor implementation.</p>
 */
public final class TaskBasedPhysicsBatchHarness {

    private static final int TASK_COUNT = 2;
    private static final int PHASE_COUNT = 2;

    private TaskBasedPhysicsBatchHarness() {
    }

    public static void main(String[] args) {
        var state = new VerificationState();
        try (var executor = new ValidationBatchExecutor(TASK_COUNT)) {
            for (int phase = 0; phase < PHASE_COUNT; phase++) {
                final int currentPhase = phase;
                List<Runnable> tasks = new ArrayList<Runnable>(TASK_COUNT);
                tasks.add(() -> state.executeChunk(0, currentPhase));
                tasks.add(() -> state.executeChunk(1, currentPhase));

                // Mirror the task-based engine phase boundary: every chunk is
                // submitted before the coordinator waits and commits.
                executor.executeAll(tasks);
                state.commitPhase(currentPhase);
            }
        }
        state.assertFinalState();
    }

    /** Java 11/JPF-compatible model of a fixed pool plus one batch barrier. */
    private static final class ValidationBatchExecutor implements AutoCloseable {

        private final ExecutorService executor;

        private ValidationBatchExecutor(int poolSize) {
            executor = Executors.newFixedThreadPool(poolSize);
        }

        private void executeAll(List<? extends Runnable> tasks) {
            var completion = new BatchCompletion(tasks.size());
            for (var task : tasks) {
                executor.execute(() -> {
                    try {
                        task.run();
                        completion.complete();
                    } catch (RuntimeException ex) {
                        completion.fail(ex);
                    } catch (Error error) {
                        completion.fail(error);
                    }
                });
            }
            completion.await();
        }

        @Override
        public void close() {
            executor.shutdown();
        }
    }

    /** Bounded barrier state exposed to JPF through ordinary monitor actions. */
    private static final class BatchCompletion {

        private int remaining;
        private Throwable failure;

        private BatchCompletion(int remaining) {
            this.remaining = remaining;
        }

        private synchronized void complete() {
            remaining--;
            notifyAll();
        }

        private synchronized void fail(Throwable failure) {
            if (this.failure == null) {
                this.failure = failure;
            }
            remaining--;
            notifyAll();
        }

        private synchronized void await() {
            while (remaining > 0) {
                try {
                    wait();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while awaiting task batch", ex);
                }
            }
            if (failure instanceof RuntimeException) {
                throw (RuntimeException) failure;
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
        }
    }

    /** Records bounded task effects while JPF explores executor interleavings. */
    private static final class VerificationState {

        private final int[] executions = new int[TASK_COUNT];
        private int committedPhases;

        synchronized void executeChunk(int taskId, int phase) {
            assert phase == committedPhases
                    : "a task executed a phase that the coordinator did not open";
            assert executions[taskId] == phase
                    : "one task executed a chunk more than once in a phase";
            executions[taskId]++;
        }

        synchronized void commitPhase(int phase) {
            assert phase == committedPhases : "phases must commit in order";
            for (int taskId = 0; taskId < TASK_COUNT; taskId++) {
                assert executions[taskId] == phase + 1
                        : "the coordinator committed before every task completed";
            }
            committedPhases++;
        }

        synchronized void assertFinalState() {
            assert committedPhases == PHASE_COUNT : "not every phase was committed";
            for (int taskId = 0; taskId < TASK_COUNT; taskId++) {
                assert executions[taskId] == PHASE_COUNT
                        : "a task did not execute exactly one chunk per phase";
            }
        }
    }
}
