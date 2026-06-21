package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkCsvWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesStableHeadersAndSessionRows() throws Exception {
        var config = BenchmarkConfig.defaults()
                .withImplementation(BenchmarkConfig.ImplementationType.SEQUENTIAL)
                .withBalls(100)
                .withThreads(1)
                .withSteps(10)
                .withSeed(42L)
                .withWarmupRuns(1)
                .withMeasuredRuns(2)
                .withOutputDir(tempDir);
        var rawResults = List.of(
                BenchmarkRunResult.success(1, true, 10_000_000L, 10, 11L),
                BenchmarkRunResult.success(2, false, 20_000_000L, 10, 11L,
                        new BenchmarkInstrumentation(1.500000, 2.500000, 3.500000, 4.500000, 6L, 7L)),
                BenchmarkRunResult.failure(3, false, 30_000_000L, "correctness check failed: mismatch"));
        var summary = BenchmarkRunner.summarize(config, rawResults);

        var export = BenchmarkCsvWriter.export(config, rawResults, summary);

        assertEquals(tempDir.resolve(BenchmarkCsvWriter.RUNS_FILE_NAME), export.runsFile());
        assertEquals(tempDir.resolve(BenchmarkCsvWriter.SUMMARY_FILE_NAME), export.summaryFile());

        var runsLines = Files.readAllLines(export.runsFile());
        var summaryLines = Files.readAllLines(export.summaryFile());
        assertEquals("timestamp,implementation,balls,threads,steps,seed,runIndex,elapsedMillis,throughputStepsPerSec,cpuUtilizationPercent,checksum,status,failureReason,syncTimeMillis,aggregationTimeMillis,taskSubmissionTimeMillis,joinOrFutureWaitMillis,lockAcquisitions,submittedTasks", runsLines.get(0));
        assertEquals("implementation,balls,threads,steps,runs,meanMillis,minMillis,maxMillis,stdDevMillis,meanThroughput,meanCpuUtilizationPercent,speedup,efficiency", summaryLines.get(0));
        assertTrue(runsLines.get(1).startsWith("20"));
        assertTrue(runsLines.get(1).contains("sequential,100,1,10,42,1,"));
        assertTrue(runsLines.get(2).contains(",1.500000,2.500000,3.500000,4.500000,6,7"));
        assertTrue(runsLines.get(3).contains(",FAILED,correctness check failed: mismatch,"));
        assertTrue(summaryLines.get(1).startsWith("sequential,100,1,10,2,20.000000,20.000000,20.000000,0.000000,500.000000,"));
        assertTrue(summaryLines.get(1).contains(",1.000000,1.000000"));
    }

    @Test
    void writesConcurrentSummaryWithSpeedupAndEfficiency() throws Exception {
        var sequentialConfig = BenchmarkConfig.defaults()
                .withImplementation(BenchmarkConfig.ImplementationType.SEQUENTIAL)
                .withBalls(100)
                .withThreads(1)
                .withSteps(10)
                .withOutputDir(tempDir);
        var concurrentConfig = sequentialConfig
                .withImplementation(BenchmarkConfig.ImplementationType.THREADS)
                .withThreads(4);

        var sequentialSummary = BenchmarkRunner.summarize(sequentialConfig, List.of(
                BenchmarkRunResult.success(1, false, 10_000_000L, 10, 10L)));
        var concurrentSummary = BenchmarkRunner.summarize(concurrentConfig, List.of(
                BenchmarkRunResult.success(1, false, 5_000_000L, 10, 10L)));

        BenchmarkCsvWriter.export(sequentialConfig, List.of(
                BenchmarkRunResult.success(1, false, 10_000_000L, 10, 10L)), sequentialSummary);
        BenchmarkCsvWriter.export(concurrentConfig, List.of(
                BenchmarkRunResult.success(1, false, 5_000_000L, 10, 10L)), concurrentSummary, sequentialSummary);

        var summaryLines = Files.readAllLines(tempDir.resolve(BenchmarkCsvWriter.SUMMARY_FILE_NAME));
        assertEquals(3, summaryLines.size());
        assertTrue(summaryLines.get(2).contains("threads,100,4,10,1,"));
        assertTrue(summaryLines.get(2).contains(",2.000000,0.500000"));
    }
}
