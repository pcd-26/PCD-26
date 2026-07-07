package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BenchmarkConfigTest {

    @Test
    void defaultMatrixCoversAllRequestedImplementationsBallCountsAndWorkerCounts() {
        var matrix = BenchmarkConfig.defaultMatrix();
        var workerMatrix = BenchmarkConfig.workerMatrix();

        assertEquals(12 + 24 * workerMatrix.size(), matrix.size());
        assertTrue(matrix.stream().anyMatch(config ->
                config.implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL
                        && config.balls() == 100
                        && config.threads() == 1));
        assertTrue(matrix.stream().anyMatch(config ->
                config.implementation() == BenchmarkConfig.ImplementationType.THREADS
                        && config.balls() == 2_500
                        && config.threads() == Runtime.getRuntime().availableProcessors()));
        assertTrue(matrix.stream().anyMatch(config ->
                config.implementation() == BenchmarkConfig.ImplementationType.EXECUTOR
                        && config.balls() == 2_000
                        && config.threads() == 8));
        assertEquals(workerMatrix, workerMatrix.stream().distinct().toList());
        assertTrue(workerMatrix.contains(1));
        assertTrue(workerMatrix.contains(2));
        assertTrue(workerMatrix.contains(4));
        assertTrue(workerMatrix.contains(8));
        assertTrue(workerMatrix.contains(Runtime.getRuntime().availableProcessors()));
        assertTrue(workerMatrix.contains(Runtime.getRuntime().availableProcessors() + 1));
    }

    @Test
    void workerMatrixRemovesDuplicatesAndSkipsInvalidCounts() {
        var workerMatrix = BenchmarkConfig.workerMatrix();

        assertEquals(workerMatrix.size(), workerMatrix.stream().distinct().count());
        assertTrue(workerMatrix.stream().allMatch(value -> value > 0));
    }

    @Test
    void invalidBallCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new BenchmarkConfig(
                        BenchmarkConfig.ImplementationType.SEQUENTIAL,
                        0,
                        1,
                        1,
                        0L,
                        1,
                        1,
                        false,
                        false,
                        Path.of("target")));
    }

    @Test
    void invalidThreadCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                BenchmarkConfig.defaults().withThreads(0));
    }

    @Test
    void invalidStepCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                BenchmarkConfig.defaults().withSteps(0));
    }

    @Test
    void missingImplementationIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new BenchmarkConfig(
                        null,
                        1,
                        1,
                        1,
                        0L,
                        1,
                        1,
                        false,
                        false,
                        Path.of("target")));
    }
}
