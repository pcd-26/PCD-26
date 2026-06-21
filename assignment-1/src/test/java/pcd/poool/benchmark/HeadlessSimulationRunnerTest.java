package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class HeadlessSimulationRunnerTest {

    private static final int BALL_COUNT = 25;
    private static final int SIMULATION_STEPS = 4;
    private static final int THREAD_COUNT = 2;
    private static final long SEED = 12345L;

    @Test
    @Timeout(5)
    void sequentialImplementationRunsHeadlessly() {
        var result = HeadlessSimulationRunner.run(
                new BenchmarkConfig(
                        BenchmarkConfig.ImplementationType.SEQUENTIAL,
                        BALL_COUNT,
                        THREAD_COUNT,
                        SIMULATION_STEPS,
                        SEED,
                        1,
                        1,
                        false,
                        false,
                        java.nio.file.Path.of("target")));

        assertEquals(SIMULATION_STEPS, result.completedSteps());
        assertEquals(1, result.config().effectiveThreads());
        assertTrue(result.elapsedNanos() > 0);
        assertNotEquals(0L, result.stateHash());
    }

    @Test
    @Timeout(5)
    void threadedImplementationRunsHeadlessly() {
        var result = HeadlessSimulationRunner.run(
                new BenchmarkConfig(
                        BenchmarkConfig.ImplementationType.THREADS,
                        BALL_COUNT,
                        THREAD_COUNT,
                        SIMULATION_STEPS,
                        SEED,
                        1,
                        1,
                        false,
                        false,
                        java.nio.file.Path.of("target")));

        assertEquals(SIMULATION_STEPS, result.completedSteps());
        assertEquals(THREAD_COUNT, result.config().effectiveThreads());
        assertTrue(result.elapsedNanos() > 0);
        assertNotEquals(0L, result.stateHash());
    }

    @Test
    @Timeout(5)
    void executorImplementationRunsHeadlessly() {
        var result = HeadlessSimulationRunner.run(
                new BenchmarkConfig(
                        BenchmarkConfig.ImplementationType.EXECUTOR,
                        BALL_COUNT,
                        THREAD_COUNT,
                        SIMULATION_STEPS,
                        SEED,
                        1,
                        1,
                        false,
                        false,
                        java.nio.file.Path.of("target")));

        assertEquals(SIMULATION_STEPS, result.completedSteps());
        assertEquals(THREAD_COUNT, result.config().effectiveThreads());
        assertTrue(result.elapsedNanos() > 0);
        assertNotEquals(0L, result.stateHash());
    }

    @Test
    @Timeout(5)
    void repeatedSequentialRunsWithTheSameSeedProduceTheSameHash() {
        var first = HeadlessSimulationRunner.run(
                BenchmarkConfig.defaults()
                        .withImplementation(BenchmarkConfig.ImplementationType.SEQUENTIAL)
                        .withBalls(BALL_COUNT)
                        .withThreads(THREAD_COUNT)
                        .withSteps(SIMULATION_STEPS)
                        .withSeed(SEED));
        var second = HeadlessSimulationRunner.run(
                BenchmarkConfig.defaults()
                        .withImplementation(BenchmarkConfig.ImplementationType.SEQUENTIAL)
                        .withBalls(BALL_COUNT)
                        .withThreads(THREAD_COUNT)
                        .withSteps(SIMULATION_STEPS)
                        .withSeed(SEED));

        assertEquals(first.stateHash(), second.stateHash());
    }
}
