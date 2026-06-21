package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkSuiteTest {

    @TempDir
    Path tempDir;

    @Test
    void buildMatrixCreatesTimestampedOutputDirectoryAndExpectedConfigurations() throws Exception {
        Instant timestamp = Instant.parse("2026-06-21T13:15:30Z");

        var configs = BenchmarkSuite.buildMatrix(tempDir, timestamp);

        assertEquals(55, configs.size());
        Path expectedOutputDir = tempDir.resolve("20260621-131530-000");
        assertTrue(Files.isDirectory(expectedOutputDir));
        assertTrue(configs.stream().allMatch(config -> config.outputDir().equals(expectedOutputDir)));
        assertEquals(5, configs.stream().filter(config -> config.implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL).count());
        assertEquals(25, configs.stream().filter(config -> config.implementation() == BenchmarkConfig.ImplementationType.THREADS).count());
        assertEquals(25, configs.stream().filter(config -> config.implementation() == BenchmarkConfig.ImplementationType.EXECUTOR).count());
        assertEquals(5, configs.stream().filter(config -> config.implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL && config.threads() == 1).count());
    }

    @Test
    void runContinuesAfterScenarioFailureAndPreservesEarlierResults() throws Exception {
        Path outputDir = tempDir.resolve("suite-results");
        var configs = List.of(
                BenchmarkConfig.defaults()
                        .withImplementation(BenchmarkConfig.ImplementationType.SEQUENTIAL)
                        .withBalls(100)
                        .withThreads(1)
                        .withWarmupRuns(0)
                        .withMeasuredRuns(2)
                        .withOutputDir(outputDir),
                BenchmarkConfig.defaults()
                        .withImplementation(BenchmarkConfig.ImplementationType.THREADS)
                        .withBalls(100)
                        .withThreads(2)
                        .withWarmupRuns(0)
                        .withMeasuredRuns(2)
                        .withOutputDir(outputDir));

        var sequentialRuns = new AtomicInteger();
        var threadedRuns = new AtomicInteger();
        var outBuffer = new ByteArrayOutputStream();
        var errBuffer = new ByteArrayOutputStream();

        var report = BenchmarkSuite.run(
                configs,
                config -> {
                    AtomicInteger counter = config.implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL
                            ? sequentialRuns
                            : threadedRuns;
                    return () -> {
                        int invocation = counter.incrementAndGet();
                        if (config.implementation() == BenchmarkConfig.ImplementationType.THREADS && invocation == 1) {
                            throw new IllegalStateException("boom");
                        }
                        return new BenchmarkRunner.BenchmarkExecution(
                                config.implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL ? 11L : 22L,
                                BenchmarkInstrumentation.zero());
                    };
                },
                new PrintStream(outBuffer, true),
                new PrintStream(errBuffer, true),
                Instant.parse("2026-06-21T13:15:30Z"));

        assertEquals(2, report.completedConfigs());
        assertEquals(1, report.failedConfigs());

        Path runsFile = outputDir.resolve(BenchmarkCsvWriter.RUNS_FILE_NAME);
        Path summaryFile = outputDir.resolve(BenchmarkCsvWriter.SUMMARY_FILE_NAME);
        Path environmentFile = outputDir.resolve(RuntimeTelemetryCsvWriter.ENVIRONMENT_FILE_NAME);
        assertTrue(Files.exists(runsFile));
        assertTrue(Files.exists(summaryFile));
        assertTrue(Files.exists(environmentFile));

        var runsLines = Files.readAllLines(runsFile);
        var summaryLines = Files.readAllLines(summaryFile);
        assertEquals(5, runsLines.size());
        assertEquals(3, summaryLines.size());
        assertTrue(errBuffer.toString().contains("benchmark_scenario_completed_with_failures"));
        assertTrue(outBuffer.toString().contains("scenario_completed completed_scenarios=2/2"));
    }
}
