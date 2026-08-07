package pcd.poool.model.physics.taskbased;

import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.parallel.ParallelPhysicsEngine;

/** Executor Framework facade for the shared parallel physics pipeline. */
public class TaskBasedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private final ParallelPhysicsEngine engine;

    public TaskBasedPhysicsEngine() {
        this(defaultPoolSize());
    }

    public TaskBasedPhysicsEngine(int poolSize) {
        this(poolSize, PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    public TaskBasedPhysicsEngine(int poolSize, long maxStepMillis) {
        engine = new ParallelPhysicsEngine(new ExecutorRangeScheduler(poolSize), maxStepMillis);
    }

    @Override
    public void step(Board board, long elapsedMillis) {
        engine.step(board, elapsedMillis);
    }

    public ParallelPhysicsEngine.StepProfile profileStep(Board board, long elapsedMillis) {
        return engine.profileStep(board, elapsedMillis);
    }

    public int poolSize() {
        return engine.workerCount();
    }

    @Override
    public void close() {
        engine.close();
    }

    private static int defaultPoolSize() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }
}
