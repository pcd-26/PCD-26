package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BenchmarkConfigTest {

    @Test
    void defaultMatrixCoversAllRequestedImplementationsAndBallCounts() {
        var matrix = BenchmarkConfig.defaultMatrix();
        int uniqueThreadCount = (int) java.util.List.of(1, 2, 4, 8, Math.max(1, Runtime.getRuntime().availableProcessors()))
                .stream()
                .distinct()
                .count();

        assertEquals(3 * 5 * uniqueThreadCount, matrix.size());
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
