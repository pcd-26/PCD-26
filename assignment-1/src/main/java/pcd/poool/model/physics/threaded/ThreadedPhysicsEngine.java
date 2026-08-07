package pcd.poool.model.physics.threaded;

import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.common.PhysicsStepper;
import pcd.poool.model.physics.parallel.ParallelPhysicsEngine;

/** Platform-thread facade for the shared parallel physics pipeline. */
public final class ThreadedPhysicsEngine implements PhysicsStepper, AutoCloseable {

    private final ParallelPhysicsEngine engine;

    public ThreadedPhysicsEngine() {
        this(defaultWorkerCount());
    }

    public ThreadedPhysicsEngine(int workerCount) {
        this(workerCount, PhysicsDefaults.FIXED_STEP_MILLIS);
    }

    public ThreadedPhysicsEngine(int workerCount, long maxStepMillis) {
        engine = new ParallelPhysicsEngine(
                new PlatformThreadRangeScheduler(workerCount),
                maxStepMillis);
    }

    @Override
    public void step(Board board, long elapsedMillis) {
        engine.step(board, elapsedMillis);
    }

    public ParallelPhysicsEngine.StepProfile profileStep(Board board, long elapsedMillis) {
        return engine.profileStep(board, elapsedMillis);
    }

    public int workerCount() {
        return engine.workerCount();
    }

    @Override
    public void close() {
        engine.close();
    }

    private static int defaultWorkerCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }
}
