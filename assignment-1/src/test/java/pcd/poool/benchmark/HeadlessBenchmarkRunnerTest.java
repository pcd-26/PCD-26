package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessBenchmarkRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsUseTheRequestedMatrix() {
        var request = HeadlessBenchmarkRunner.defaults();

        assertEquals(List.of(
                BenchmarkConfig.ImplementationType.SEQUENTIAL,
                BenchmarkConfig.ImplementationType.THREADS,
                BenchmarkConfig.ImplementationType.EXECUTOR), request.implementations());
        assertEquals(List.of(100, 500, 1_000, 2_000, 2_500), request.balls());
        assertEquals(1_000, request.steps());
        assertEquals(42L, request.seed());
        assertTrue(request.warmupRuns() >= 2);
        assertTrue(request.measuredRuns() >= 5);
    }

    @Test
    void writesOneCsvRowPerMeasuredRunOnly() throws Exception {
        var request = HeadlessBenchmarkRunner.defaults()
                .withBalls(List.of(8, 12))
                .withSteps(6)
                .withWarmupRuns(1)
                .withMeasuredRuns(2)
                .withWorkers(2)
                .withOutputFile(tempDir.resolve("raw-results.csv"));

        var report = HeadlessBenchmarkRunner.run(request);

        assertEquals(tempDir.resolve("raw-results.csv"), report.outputFile());
        assertEquals(tempDir.resolve("aggregated-results.csv"), report.aggregatedOutputFile());
        assertEquals(tempDir.resolve("speedup-results.csv"), report.speedupOutputFile());
        assertEquals(12, report.rows().size());

        var lines = Files.readAllLines(report.outputFile());
        assertEquals("implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,coordinationMs,coordinationRatio,tasksSubmitted,stateHash,jvm,os,availableProcessors", lines.get(0));
        assertEquals(13, lines.size());
        assertTrue(Files.exists(tempDir.resolve(RuntimeTelemetryCsvWriter.ENVIRONMENT_FILE_NAME)));
        assertTrue(lines.stream().skip(1).allMatch(line -> line.contains(",false,")));
        assertTrue(report.rows().stream().allMatch(row -> row.elapsedMs() > 0.0));
        assertTrue(report.rows().stream().allMatch(row -> row.availableProcessors() > 0));
        assertTrue(report.rows().stream().filter(row -> row.implementation().equals("sequential")).allMatch(row -> row.coordinationMs() == 0.0 && row.coordinationRatio() == 0.0 && row.tasksSubmitted() == 0L));
        assertTrue(report.rows().stream().filter(row -> !row.implementation().equals("sequential")).anyMatch(row -> row.coordinationMs() >= 0.0));
        assertTrue(Files.exists(report.aggregatedOutputFile()));
        assertTrue(Files.exists(report.speedupOutputFile()));
    }

    @Test
    void sameSeedProducesSameFinalStateAcrossAllImplementations() throws Exception {
        var request = HeadlessBenchmarkRunner.defaults()
                .withBalls(List.of(20))
                .withSteps(4)
                .withWarmupRuns(1)
                .withMeasuredRuns(1)
                .withWorkers(2)
                .withOutputFile(tempDir.resolve("raw-results.csv"));

        var report = HeadlessBenchmarkRunner.run(request);

        long sequential = stateHash(report.rows(), "sequential");
        long threaded = stateHash(report.rows(), "threads");
        long executor = stateHash(report.rows(), "executor");

        assertEquals(sequential, threaded);
        assertEquals(sequential, executor);
    }

    private static long stateHash(List<HeadlessBenchmarkRunner.BenchmarkRow> rows, String implementation) {
        return rows.stream()
                .filter(row -> row.implementation().equals(implementation))
                .findFirst()
                .map(HeadlessBenchmarkRunner.BenchmarkRow::stateHash)
                .orElseThrow();
    }
}
