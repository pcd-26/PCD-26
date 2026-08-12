package pcd.poool.benchmark.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pcd.poool.benchmark.config.BenchmarkConfig;
import pcd.poool.benchmark.core.BenchmarkRunResult;
import pcd.poool.benchmark.io.RuntimeTelemetryCsvWriter;

class ScalabilityBenchmarkRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsUseConcurrentImplementationsAndWorkerMatrix() {
        var request = ScalabilityBenchmarkRunner.defaults();

        assertEquals(List.of(
                BenchmarkConfig.ImplementationType.SEQUENTIAL,
                BenchmarkConfig.ImplementationType.THREADS,
                BenchmarkConfig.ImplementationType.EXECUTOR), request.implementations());
        assertEquals(List.of(2_500), request.balls());
        assertEquals(BenchmarkConfig.workerMatrix(), request.workerCounts());
        assertEquals(1_000, request.steps());
        assertEquals(42L, request.seed());
        assertTrue(request.warmupRuns() >= 2);
        assertTrue(request.measuredRuns() >= 5);
    }

    @Test
    void writesRawAndAggregatedScalabilityCsvWithoutSequentialRows() throws Exception {
        var request = ScalabilityBenchmarkRunner.defaults()
                .withBalls(List.of(2_500))
                .withWorkerCounts(List.of(1, 2))
                .withSteps(6)
                .withWarmupRuns(1)
                .withMeasuredRuns(2)
                .withOutputFile(tempDir.resolve("raw-scalability-results.csv"));

        var report = ScalabilityBenchmarkRunner.run(request);

        assertEquals(tempDir.resolve("raw-scalability-results.csv"), report.outputFile());
        assertEquals(tempDir.resolve("aggregated-scalability-results.csv"), report.aggregatedOutputFile());
        assertEquals(15, report.rawResults().size());
        assertEquals(5, report.rawResults().stream().filter(BenchmarkRunResult::warmup).count());
        assertEquals(10, report.rows().size());
        assertTrue(report.rows().stream().anyMatch(row -> row.implementation().equals("sequential")));
        assertEquals(Set.of("sequential", "threads", "executor"), report.rows().stream().map(ScalabilityBenchmarkRunner.BenchmarkRow::implementation).collect(Collectors.toSet()));
        assertEquals(Set.of(1, 2), report.rows().stream().map(ScalabilityBenchmarkRunner.BenchmarkRow::workers).collect(Collectors.toSet()));

        var rawLines = Files.readAllLines(report.outputFile());
        var aggregatedLines = Files.readAllLines(report.aggregatedOutputFile());
        assertEquals("implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,coordinationMs,coordinationRatio,tasksSubmitted,jvm,os,availableProcessors", rawLines.get(0));
        assertEquals("implementation,balls,workers,steps,seed,meanElapsedMs,medianElapsedMs,stdElapsedMs,meanThroughput,medianThroughput,stdThroughput,meanCoordinationMs,medianCoordinationMs,stdCoordinationMs,meanCoordinationRatio,medianCoordinationRatio,stdCoordinationRatio,meanTasksSubmitted", aggregatedLines.get(0));
        assertEquals(6, aggregatedLines.size());
        assertTrue(Files.exists(tempDir.resolve(RuntimeTelemetryCsvWriter.ENVIRONMENT_FILE_NAME)));
        assertTrue(report.rows().stream().allMatch(row -> row.coordinationMs() == 0.0 && row.coordinationRatio() == 0.0 && row.tasksSubmitted() == 0L));
    }
}
