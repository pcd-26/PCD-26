package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalabilityBenchmarkRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsUseConcurrentImplementationsAndWorkerMatrix() {
        var request = ScalabilityBenchmarkRunner.defaults();

        assertEquals(List.of(
                BenchmarkConfig.ImplementationType.THREADS,
                BenchmarkConfig.ImplementationType.EXECUTOR), request.implementations());
        assertEquals(List.of(2_500, 10_000), request.balls());
        assertEquals(List.of(1, 2, 4, 8, Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().availableProcessors() + 1), request.workerCounts());
        assertEquals(1_000, request.steps());
        assertEquals(42L, request.seed());
        assertTrue(request.warmupRuns() >= 2);
        assertTrue(request.measuredRuns() >= 5);
    }

    @Test
    void writesRawAndAggregatedScalabilityCsvWithoutSequentialRows() throws Exception {
        var request = ScalabilityBenchmarkRunner.defaults()
                .withBalls(List.of(2_500, 10_000))
                .withWorkerCounts(List.of(1, 2))
                .withSteps(6)
                .withWarmupRuns(1)
                .withMeasuredRuns(2)
                .withOutputFile(tempDir.resolve("raw-scalability-results.csv"));

        var report = ScalabilityBenchmarkRunner.run(request);

        assertEquals(tempDir.resolve("raw-scalability-results.csv"), report.outputFile());
        assertEquals(tempDir.resolve("aggregated-scalability-results.csv"), report.aggregatedOutputFile());
        assertEquals(16, report.rows().size());
        assertTrue(report.rows().stream().noneMatch(row -> row.implementation().equals("sequential")));
        assertEquals(Set.of("threads", "executor"), report.rows().stream().map(ScalabilityBenchmarkRunner.BenchmarkRow::implementation).collect(Collectors.toSet()));
        assertEquals(Set.of(1, 2), report.rows().stream().map(ScalabilityBenchmarkRunner.BenchmarkRow::workers).collect(Collectors.toSet()));

        var rawLines = Files.readAllLines(report.outputFile());
        var aggregatedLines = Files.readAllLines(report.aggregatedOutputFile());
        assertEquals("implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,coordinationMs,coordinationRatio,tasksSubmitted,jvm,os,availableProcessors", rawLines.get(0));
        assertEquals("implementation,balls,workers,steps,seed,avgElapsedMs,stdElapsedMs,avgThroughput,stdThroughput,avgCoordinationMs,stdCoordinationMs,avgCoordinationRatio,stdCoordinationRatio,avgTasksSubmitted", aggregatedLines.get(0));
        assertEquals(9, aggregatedLines.size());
        assertTrue(report.rows().stream().filter(row -> row.implementation().equals("threads") || row.implementation().equals("executor")).allMatch(row -> row.coordinationMs() >= 0.0 && row.coordinationRatio() >= 0.0 && row.tasksSubmitted() >= 0L));
    }
}
