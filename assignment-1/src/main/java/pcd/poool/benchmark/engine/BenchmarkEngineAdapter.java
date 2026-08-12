package pcd.poool.benchmark.engine;

import java.util.OptionalInt;
import pcd.poool.benchmark.core.BenchmarkRunner;
import pcd.poool.model.physics.common.Board;
import pcd.poool.model.physics.common.PhysicsStepper;

/**
 * Common adapter for benchmarked physics engines.
 *
 * <p>The adapter owns engine creation and teardown so benchmark runners can
 * build a fresh engine per run without knowing whether the implementation is
 * sequential, platform-threaded, or task-based.
 */
public interface BenchmarkEngineAdapter {

    /**
     * Gets the logical engine name used in benchmark output.
     *
     * @return engine name
     */
    String engineName();

    /**
     * Gets the configured worker count when the engine exposes one.
     *
     * @return worker count, or empty for the sequential baseline
     */
    OptionalInt workerCount();

    /**
     * Creates a fresh engine session for one benchmark run.
     *
     * @return new engine session
     */
    BenchmarkEngineSession open();

    /**
     * Session backed by one fresh engine instance.
     */
    interface BenchmarkEngineSession extends AutoCloseable {

        /**
         * Gets the stepper used by the board for this session.
         *
         * @return engine stepper
         */
        PhysicsStepper stepper();

        /**
         * Executes the benchmark workload on the provided board.
         *
         * @param board board to advance
         * @param steps number of simulation ticks to run
         * @return benchmark execution result
         */
        default BenchmarkRunner.BenchmarkExecution execute(Board board, int steps) {
            return execute(board, steps, false);
        }

        /**
         * Executes the benchmark workload on the provided board.
         *
         * @param board board to advance
         * @param steps number of simulation ticks to run
         * @param instrumentationEnabled whether to collect per-step profiling data
         * @return benchmark execution result
         */
        BenchmarkRunner.BenchmarkExecution execute(Board board, int steps, boolean instrumentationEnabled);

        @Override
        void close() throws Exception;
    }
}
