package pcd.poool.benchmark;

import java.util.OptionalInt;
import pcd.poool.model.physics.common.Ball;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.sequential.PhysicsEngine;
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;

/**
 * Factory methods for benchmark engine adapters.
 */
public final class BenchmarkEngineAdapters {

    private BenchmarkEngineAdapters() {
    }

    /**
     * Creates the sequential baseline adapter.
     *
     * @return sequential adapter
     */
    public static BenchmarkEngineAdapter sequential() {
        return new SequentialEngineAdapter();
    }

    /**
     * Creates the platform-threaded adapter.
     *
     * @param workerCount worker count used by the engine
     * @return threaded adapter
     */
    public static BenchmarkEngineAdapter threaded(int workerCount) {
        return new ThreadedEngineAdapter(workerCount);
    }

    /**
     * Creates the task-based adapter.
     *
     * @param workerCount worker count used by the engine
     * @return task-based adapter
     */
    public static BenchmarkEngineAdapter taskBased(int workerCount) {
        return new TaskBasedEngineAdapter(workerCount);
    }

    /**
     * Creates the adapter that matches a benchmark implementation type.
     *
     * @param implementation benchmark implementation
     * @param workerCount worker count to use for concurrent implementations
     * @return matching adapter
     */
    public static BenchmarkEngineAdapter forImplementation(BenchmarkConfig.ImplementationType implementation, int workerCount) {
        return switch (implementation) {
            case SEQUENTIAL -> sequential();
            case THREADS -> threaded(workerCount);
            case EXECUTOR -> taskBased(workerCount);
        };
    }

    private static final class SequentialEngineAdapter implements BenchmarkEngineAdapter {

        @Override
        public String engineName() {
            return "sequential";
        }

        @Override
        public OptionalInt workerCount() {
            return OptionalInt.empty();
        }

        @Override
        public BenchmarkEngineSession open() {
            return new Session(new PhysicsEngine());
        }
    }

    private static final class ThreadedEngineAdapter implements BenchmarkEngineAdapter {

        private final int workerCount;

        private ThreadedEngineAdapter(int workerCount) {
            if (workerCount <= 0) {
                throw new IllegalArgumentException("workerCount must be > 0");
            }
            this.workerCount = workerCount;
        }

        @Override
        public String engineName() {
            return "threads";
        }

        @Override
        public OptionalInt workerCount() {
            return OptionalInt.of(workerCount);
        }

        @Override
        public BenchmarkEngineSession open() {
            return new Session(new ThreadedPhysicsEngine(workerCount));
        }
    }

    private static final class TaskBasedEngineAdapter implements BenchmarkEngineAdapter {

        private final int workerCount;

        private TaskBasedEngineAdapter(int workerCount) {
            if (workerCount <= 0) {
                throw new IllegalArgumentException("workerCount must be > 0");
            }
            this.workerCount = workerCount;
        }

        @Override
        public String engineName() {
            return "executor";
        }

        @Override
        public OptionalInt workerCount() {
            return OptionalInt.of(workerCount);
        }

        @Override
        public BenchmarkEngineSession open() {
            return new Session(new TaskBasedPhysicsEngine(workerCount));
        }
    }

    private static final class Session implements BenchmarkEngineAdapter.BenchmarkEngineSession {

        private final PhysicsStepper stepper;
        private final ThreadedPhysicsEngine threadedEngine;
        private final TaskBasedPhysicsEngine taskBasedEngine;

        private Session(PhysicsStepper stepper) {
            this.stepper = stepper;
            this.threadedEngine = stepper instanceof ThreadedPhysicsEngine engine ? engine : null;
            this.taskBasedEngine = stepper instanceof TaskBasedPhysicsEngine engine ? engine : null;
        }

        @Override
        public PhysicsStepper stepper() {
            return stepper;
        }

        @Override
        public BenchmarkRunner.BenchmarkExecution execute(Board board, int steps, boolean instrumentationEnabled) {
            BenchmarkInstrumentation instrumentation = BenchmarkInstrumentation.zero();
            for (int i = 0; i < steps; i++) {
                if (instrumentationEnabled && threadedEngine != null) {
                    instrumentation = instrumentation.plus(toInstrumentation(threadedEngine.profileStep(board, PhysicsDefaults.FIXED_STEP_MILLIS)));
                } else if (instrumentationEnabled && taskBasedEngine != null) {
                    instrumentation = instrumentation.plus(toInstrumentation(taskBasedEngine.profileStep(board, PhysicsDefaults.FIXED_STEP_MILLIS)));
                } else {
                    step(board);
                }
            }
            return new BenchmarkRunner.BenchmarkExecution(BenchmarkStateHasher.checksum(board), instrumentation);
        }

        private void step(Board board) {
            if (threadedEngine != null) {
                threadedEngine.step(board, PhysicsDefaults.FIXED_STEP_MILLIS);
            } else if (taskBasedEngine != null) {
                taskBasedEngine.step(board, PhysicsDefaults.FIXED_STEP_MILLIS);
            } else {
                board.updateState(PhysicsDefaults.FIXED_STEP_MILLIS);
            }
        }

        @Override
        public void close() throws Exception {
            if (threadedEngine != null) {
                threadedEngine.close();
            }
            if (taskBasedEngine != null) {
                taskBasedEngine.close();
            }
        }
    }

    private static BenchmarkInstrumentation toInstrumentation(ThreadedPhysicsEngine.StepProfile profile) {
        if (profile == null) {
            return BenchmarkInstrumentation.zero();
        }
        return new BenchmarkInstrumentation(
                profile.syncTimeMillis(),
                profile.aggregationTimeMillis(),
                profile.taskSubmissionTimeMillis(),
                profile.joinOrFutureWaitMillis(),
                profile.lockAcquisitions(),
                profile.submittedTasks(),
                profile.stateReadMillis(),
                profile.partitionMillis(),
                profile.movementMillis(),
                profile.holeInteractionMillis(),
                profile.collisionDetectionMillis(),
                profile.collisionResolutionMillis(),
                profile.mergeApplyMillis());
    }

    private static BenchmarkInstrumentation toInstrumentation(TaskBasedPhysicsEngine.StepProfile profile) {
        if (profile == null) {
            return BenchmarkInstrumentation.zero();
        }
        return new BenchmarkInstrumentation(
                profile.syncTimeMillis(),
                profile.aggregationTimeMillis(),
                profile.taskSubmissionTimeMillis(),
                profile.joinOrFutureWaitMillis(),
                profile.lockAcquisitions(),
                profile.submittedTasks(),
                profile.stateReadMillis(),
                profile.partitionMillis(),
                profile.movementMillis(),
                profile.holeInteractionMillis(),
                profile.collisionDetectionMillis(),
                profile.collisionResolutionMillis(),
                profile.mergeApplyMillis());
    }
}
