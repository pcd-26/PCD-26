package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void runsAllStepsInOrderAndProducesExpectedFiles() throws Exception {
        var events = new ArrayList<String>();
        var out = new PrintStream(new ByteArrayOutputStream(), true);
        var request = new BenchmarkPipeline.BenchmarkPipelineRequest(
                tempDir.resolve("results"),
                tempDir.resolve("charts"),
                Instant.parse("2026-06-21T13:15:30Z"),
                out);

        var report = BenchmarkPipeline.run(request, new BenchmarkPipeline.BenchmarkPipelineSteps() {
            @Override
            public HeadlessBenchmarkRunner.BenchmarkReport runHeadless(HeadlessBenchmarkRunner.BenchmarkRequest benchmarkRequest) throws IOException {
                events.add("headless");
                Files.createDirectories(benchmarkRequest.outputFile().getParent());
                writeCsv(benchmarkRequest.outputFile(), List.of(
                        "implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,coordinationMs,coordinationRatio,tasksSubmitted,stateHash,jvm,os,availableProcessors",
                        "sequential,100,1,10,42,1,false,10.000000,1000.000000,0.000000,0.000000,0,JVM,OS,8"));
                var aggregated = benchmarkRequest.outputFile().getParent().resolve("aggregated-results.csv");
                var speedup = benchmarkRequest.outputFile().getParent().resolve("speedup-results.csv");
                writeCsv(aggregated, List.of(
                        "implementation,balls,workers,steps,seed,meanElapsedMs,medianElapsedMs,stdElapsedMs,meanThroughput,medianThroughput,stdThroughput,meanCoordinationMs,medianCoordinationMs,stdCoordinationMs,meanCoordinationRatio,medianCoordinationRatio,stdCoordinationRatio,meanTasksSubmitted",
                        "sequential,100,1,10,42,10.000000,10.000000,0.000000,1000.000000,1000.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000"));
                writeCsv(speedup, List.of(
                        "balls,workers,seed,implementation,meanSequentialMs,meanParallelMs,speedup",
                        "100,1,42,sequential,10.000000,10.000000,1.000000"));
                return new HeadlessBenchmarkRunner.BenchmarkReport(benchmarkRequest.outputFile(), aggregated, speedup, List.of(), List.of());
            }

            @Override
            public BenchmarkSuite.SuiteReport runSuite(Path resultsRoot) throws Exception {
                events.add("suite");
                Path outputDir = resultsRoot;
                Files.createDirectories(outputDir);
                writeCsv(outputDir.resolve(BenchmarkCsvWriter.RUNS_FILE_NAME), List.of(
                        "timestamp,implementation,balls,threads,steps,seed,runIndex,elapsedMillis,throughputStepsPerSec,cpuUtilizationPercent,checksum,status,failureReason,syncTimeMillis,aggregationTimeMillis,taskSubmissionTimeMillis,joinOrFutureWaitMillis,lockAcquisitions,submittedTasks,stateReadTimeMillis,partitionTimeMillis,movementTimeMillis,holeInteractionTimeMillis,collisionDetectionTimeMillis,collisionResolutionTimeMillis,mergeApplyTimeMillis",
                        "2026-06-21T13:15:30Z,sequential,100,1,10,42,1,10.000000,1000.000000,50.000000,11,SUCCESS,,0.000000,0.000000,0.000000,0.000000,0,0,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000"));
                writeCsv(outputDir.resolve(RuntimeTelemetryCsvWriter.ENVIRONMENT_FILE_NAME), List.of(
                        "availableProcessors,cpuModel,physicalCores,logicalCpuCount,totalPhysicalMemoryBytes,jvmName,jvmVersion,osName,osVersion,osArch,maxMemoryBytes,totalMemoryBytes,freeMemoryBytes,processCpuTimeSupported,processCpuTimeNanos",
                        "8,Test CPU,4,8,17179869184,JVM,17,OS,1,amd64,1,1,1,true,123"));
                return new BenchmarkSuite.SuiteReport(outputDir, 1, 0);
            }

            @Override
            public ScalabilityBenchmarkRunner.BenchmarkReport runScalability(ScalabilityBenchmarkRunner.BenchmarkRequest benchmarkRequest) throws IOException {
                events.add("scalability");
                Files.createDirectories(benchmarkRequest.outputFile().getParent());
                writeCsv(benchmarkRequest.outputFile(), List.of(
                        "implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,coordinationMs,coordinationRatio,tasksSubmitted,jvm,os,availableProcessors",
                        "threads,100,1,10,42,1,false,10.000000,1000.000000,1.000000,0.100000,1,JVM,OS,8"));
                var aggregated = benchmarkRequest.outputFile().getParent().resolve("aggregated-scalability-results.csv");
                writeCsv(aggregated, List.of(
                        "implementation,balls,workers,steps,seed,meanElapsedMs,medianElapsedMs,stdElapsedMs,meanThroughput,medianThroughput,stdThroughput,meanCoordinationMs,medianCoordinationMs,stdCoordinationMs,meanCoordinationRatio,medianCoordinationRatio,stdCoordinationRatio,meanTasksSubmitted",
                        "threads,100,1,10,42,10.000000,10.000000,0.000000,1000.000000,1000.000000,0.000000,1.000000,1.000000,0.000000,0.100000,0.100000,0.000000,1.000000"));
                return new ScalabilityBenchmarkRunner.BenchmarkReport(benchmarkRequest.outputFile(), aggregated, List.of(), List.of());
            }

            @Override
            public void generateCharts(Path inputDir, Path outputDir) throws IOException {
                events.add("charts");
                Files.createDirectories(outputDir);
                Files.writeString(outputDir.resolve("chart.txt"), inputDir.toString());
            }
        });

        Path resultsDir = tempDir.resolve("results");
        assertEquals(resultsDir, report.resultsDir());
        assertEquals(tempDir.resolve("charts"), report.chartsDir());
        assertEquals(List.of("headless", "suite", "scalability", "charts"), events);
        assertTrue(Files.exists(resultsDir.resolve("raw-results.csv")));
        assertTrue(Files.exists(resultsDir.resolve("aggregated-results.csv")));
        assertTrue(Files.exists(resultsDir.resolve("speedup-results.csv")));
        assertTrue(Files.exists(resultsDir.resolve("raw-scalability-results.csv")));
        assertTrue(Files.exists(resultsDir.resolve("aggregated-scalability-results.csv")));
        assertFalse(Files.exists(resultsDir.resolve("raw-gui-results.csv")));
        assertFalse(Files.exists(resultsDir.resolve("aggregated-gui-results.csv")));
        assertFalse(Files.exists(resultsDir.resolve(BenchmarkCsvWriter.SUMMARY_FILE_NAME)));
        assertTrue(Files.exists(tempDir.resolve("charts").resolve("chart.txt")));
        assertFalse(Files.exists(resultsDir.resolve("missing.txt")));
        assertEquals(null, report.guiRawFile());
        assertEquals(null, report.guiAggregatedFile());
        assertEquals(null, report.guiCompatibilityFile());
    }

    @Test
    void failsFastWhenAnEarlierStepThrows() {
        var events = new ArrayList<String>();
        var request = new BenchmarkPipeline.BenchmarkPipelineRequest(
                tempDir.resolve("results"),
                tempDir.resolve("charts"),
                Instant.parse("2026-06-21T13:15:30Z"),
                new PrintStream(new ByteArrayOutputStream(), true));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> BenchmarkPipeline.run(request, new BenchmarkPipeline.BenchmarkPipelineSteps() {
            @Override
            public HeadlessBenchmarkRunner.BenchmarkReport runHeadless(HeadlessBenchmarkRunner.BenchmarkRequest benchmarkRequest) {
                events.add("headless");
                throw new IllegalStateException("boom");
            }

            @Override
            public BenchmarkSuite.SuiteReport runSuite(Path resultsRoot) {
                events.add("suite");
                throw new IllegalStateException("should not run");
            }

            @Override
            public ScalabilityBenchmarkRunner.BenchmarkReport runScalability(ScalabilityBenchmarkRunner.BenchmarkRequest benchmarkRequest) {
                events.add("scalability");
                throw new IllegalStateException("should not run");
            }

            @Override
            public void generateCharts(Path inputDir, Path outputDir) {
                events.add("charts");
                throw new IllegalStateException("should not run");
            }
        }));

        assertTrue(ex.getMessage().contains("boom"));
        assertEquals(List.of("headless"), events);
    }

    private static void writeCsv(Path file, List<String> lines) throws IOException {
        Files.writeString(file, String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
