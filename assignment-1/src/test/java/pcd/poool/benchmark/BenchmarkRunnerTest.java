package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BenchmarkRunnerTest {

    @Test
    void summaryIgnoresWarmupsAndComputesStatisticsFromMeasuredRunsOnly() {
        var config = BenchmarkConfig.defaults()
                .withWarmupRuns(2)
                .withMeasuredRuns(3)
                .withSteps(10)
                .withOutputDir(Path.of("target"));
        var results = List.of(
                BenchmarkRunResult.success(1, true, 10_000_000L, 10, 11L),
                BenchmarkRunResult.success(2, true, 20_000_000L, 10, 11L),
                BenchmarkRunResult.success(3, false, 1_000_000L, 10, 99L),
                BenchmarkRunResult.success(4, false, 3_000_000L, 10, 99L),
                BenchmarkRunResult.success(5, false, 5_000_000L, 10, 99L));

        var summary = BenchmarkRunner.summarize(config, results);

        assertEquals(config, summary.config());
        assertEquals(5, summary.totalRuns());
        assertEquals(2, summary.warmupRuns());
        assertEquals(3, summary.measuredRuns());
        assertEquals(5, summary.successfulRuns());
        assertEquals(0, summary.failedRuns());
        assertEquals(3, summary.successfulMeasuredRuns());
        assertEquals(0, summary.failedMeasuredRuns());
        assertEquals(3.0, summary.meanElapsedMillis(), 1e-9);
        assertEquals(3.0, summary.medianElapsedMillis(), 1e-9);
        assertEquals(1.0, summary.minElapsedMillis(), 1e-9);
        assertEquals(5.0, summary.maxElapsedMillis(), 1e-9);
        assertEquals(Math.sqrt(8.0 / 3.0), summary.stddevElapsedMillis(), 1e-9);
        assertEquals((10_000.0 + 3_333.3333333333335 + 2_000.0) / 3.0, summary.meanThroughputStepsPerSecond(), 1e-9);
        assertEquals(3_333.3333333333335, summary.medianThroughputStepsPerSecond(), 1e-9);
        assertEquals(99L, summary.checksum());
        assertTrue(summary.checksumStable());
    }

    @Test
    void executeTracksFailedRunsAndKeepsRawResultsSeparateFromSummary() {
        var config = BenchmarkConfig.defaults()
                .withWarmupRuns(1)
                .withMeasuredRuns(2)
                .withSteps(4)
                .withOutputDir(Path.of("target"));
        var invocations = new AtomicInteger();

        var rawResults = BenchmarkRunner.execute(config, () -> {
            int run = invocations.incrementAndGet();
            if (run == 2) {
                throw new IllegalStateException("boom");
            }
            return 7L;
        });

        assertEquals(3, rawResults.size());
        assertTrue(rawResults.get(0).warmup());
        assertTrue(rawResults.get(0).succeeded());
        assertTrue(rawResults.get(1).failed());
        assertNotNull(rawResults.get(1).failureMessage());
        assertFalse(rawResults.get(1).failureMessage().isBlank());
        assertFalse(rawResults.get(2).warmup());

        var summary = BenchmarkRunner.summarize(config, rawResults);
        assertEquals(1, summary.failedMeasuredRuns());
        assertEquals(1, summary.successfulMeasuredRuns());
        assertEquals(1, summary.failedRuns());
        assertEquals(2, summary.successfulRuns());
        assertTrue(summary.meanElapsedMillis() > 0.0);
        assertEquals(7L, summary.checksum());
        assertTrue(summary.checksumStable());
    }

    @Test
    void timeCapturesInstrumentationFromExecutionWorkload() {
        var instrumentation = new BenchmarkInstrumentation(1.25, 2.5, 3.75, 5.0, 6L, 7L);

        var result = BenchmarkRunner.time(1, false, 12, () ->
                new BenchmarkRunner.BenchmarkExecution(99L, instrumentation));

        assertTrue(result.succeeded());
        assertEquals(99L, result.checksum());
        assertEquals(instrumentation, result.instrumentation());
        assertEquals(12, result.completedSteps());
    }

    @Test
    void throughputFormulaMatchesCompletedStepsPerSecond() {
        assertEquals(2_000.0, BenchmarkRunner.throughput(500, 250_000_000L), 1e-9);
    }
}
