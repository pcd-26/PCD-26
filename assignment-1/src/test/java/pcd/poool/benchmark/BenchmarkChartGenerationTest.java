package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkChartGenerationTest {

    @TempDir
    Path tempDir;

    @Test
    void scriptGeneratesReportReadyCharts() throws Exception {
        Path inputDir = tempDir.resolve("results");
        Path outputDir = tempDir.resolve("charts").resolve("report");
        Files.createDirectories(inputDir);

        write(inputDir.resolve("aggregated-results.csv"), List.of(
                "implementation,balls,workers,steps,seed,avgElapsedMs,stdElapsedMs,avgThroughput,stdThroughput,avgCoordinationMs,stdCoordinationMs,avgCoordinationRatio,stdCoordinationRatio,avgTasksSubmitted",
                "sequential,100,1,100,1,10.000000,0.000000,1000.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000",
                "threads,100,2,100,1,6.000000,0.000000,1666.666667,0.000000,1.000000,0.000000,0.100000,0.000000,1.000000",
                "executor,100,2,100,1,7.000000,0.000000,1428.571429,0.000000,1.100000,0.000000,0.110000,0.000000,1.000000",
                "sequential,500,1,100,1,20.000000,0.000000,5000.000000,0.000000,0.000000,0.000000,0.000000,0.000000,0.000000",
                "threads,500,4,100,1,8.000000,0.000000,12500.000000,0.000000,1.500000,0.000000,0.150000,0.000000,1.000000",
                "executor,500,4,100,1,9.000000,0.000000,11111.111111,0.000000,1.600000,0.000000,0.160000,0.000000,1.000000"));

        write(inputDir.resolve("speedup-results.csv"), List.of(
                "balls,workers,implementation,avgSequentialMs,avgParallelMs,speedup",
                "100,2,threads,10.000000,6.000000,1.666667",
                "100,2,executor,10.000000,7.000000,1.428571",
                "500,4,threads,20.000000,8.000000,2.500000",
                "500,4,executor,20.000000,9.000000,2.222222"));

        write(inputDir.resolve("aggregated-scalability-results.csv"), List.of(
                "implementation,balls,workers,steps,seed,avgElapsedMs,stdElapsedMs,avgThroughput,stdThroughput,avgCoordinationMs,stdCoordinationMs,avgCoordinationRatio,stdCoordinationRatio,avgTasksSubmitted",
                "threads,100,1,100,1,12.000000,0.000000,900.000000,0.000000,0.500000,0.000000,0.041667,0.000000,1.000000",
                "threads,100,2,100,1,6.500000,0.000000,1600.000000,0.000000,0.750000,0.000000,0.115385,0.000000,1.000000",
                "threads,100,4,100,1,5.000000,0.000000,2000.000000,0.000000,1.200000,0.000000,0.240000,0.000000,1.000000",
                "executor,100,1,100,1,13.000000,0.000000,850.000000,0.000000,0.400000,0.000000,0.030769,0.000000,1.000000",
                "executor,100,2,100,1,7.000000,0.000000,1500.000000,0.000000,0.900000,0.000000,0.128571,0.000000,1.000000",
                "executor,100,4,100,1,5.500000,0.000000,1900.000000,0.000000,1.100000,0.000000,0.200000,0.000000,1.000000",
                "threads,500,1,100,1,22.000000,0.000000,4000.000000,0.000000,0.600000,0.000000,0.027273,0.000000,1.000000",
                "threads,500,2,100,1,10.000000,0.000000,10000.000000,0.000000,1.000000,0.000000,0.100000,0.000000,1.000000",
                "threads,500,4,100,1,8.500000,0.000000,11764.705882,0.000000,1.400000,0.000000,0.164706,0.000000,1.000000",
                "executor,500,1,100,1,24.000000,0.000000,3800.000000,0.000000,0.500000,0.000000,0.020833,0.000000,1.000000",
                "executor,500,2,100,1,11.000000,0.000000,9090.909091,0.000000,1.050000,0.000000,0.095455,0.000000,1.000000",
                "executor,500,4,100,1,9.000000,0.000000,11111.111111,0.000000,1.300000,0.000000,0.144444,0.000000,1.000000"));

        write(inputDir.resolve("aggregated-gui-results.csv"), List.of(
                "implementation,balls,workers,steps,seed,avgFrameMs,p95FrameMs,maxFrameMs,avgFps,avgFramesAbove16Ms,avgFramesAbove33Ms",
                "sequential,100,1,100,1,12.000000,13.000000,14.000000,83.333333,0.000000,0.000000",
                "threads,100,2,100,1,8.000000,9.000000,10.000000,125.000000,0.000000,0.000000",
                "executor,100,2,100,1,9.000000,10.000000,11.000000,111.111111,0.000000,0.000000",
                "sequential,500,1,100,1,20.000000,21.000000,22.000000,50.000000,0.000000,0.000000",
                "threads,500,4,100,1,10.000000,11.000000,12.000000,100.000000,0.000000,0.000000",
                "executor,500,4,100,1,11.000000,12.000000,13.000000,90.909091,0.000000,0.000000"));
        write(inputDir.resolve("environment.csv"), List.of(
                "availableProcessors,cpuModel,physicalCores,logicalCpuCount,totalPhysicalMemoryBytes,jvmName,jvmVersion,osName,osVersion,osArch,maxMemoryBytes,totalMemoryBytes,freeMemoryBytes,processCpuTimeSupported,processCpuTimeNanos",
                "8,Test CPU,4,8,17179869184,JVM,21,Windows 11,10.0,amd64,1,1,1,true,123"));

        write(inputDir.resolve("raw-results.csv"), List.of(
                "implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,coordinationMs,coordinationRatio,tasksSubmitted,stateHash,jvm,os,availableProcessors",
                "sequential,100,1,100,1,1,false,10.000000,1000.000000,0.000000,0.000000,0,JVM,OS,8"));
        write(inputDir.resolve("raw-scalability-results.csv"), List.of(
                "implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,coordinationMs,coordinationRatio,tasksSubmitted,jvm,os,availableProcessors",
                "threads,100,1,100,1,1,true,99.000000,900.000000,0.500000,0.005051,1,JVM,OS,8",
                "threads,100,1,100,1,2,false,12.000000,900.000000,0.500000,0.041667,1,JVM,OS,8"));
        write(inputDir.resolve("raw-gui-results.csv"), List.of(
                "implementation,balls,workers,steps,seed,runIndex,avgFrameMs,p95FrameMs,maxFrameMs,avgFps,framesAbove16Ms,framesAbove33Ms,jvm,os,availableProcessors",
                "sequential,100,1,100,1,1,12.000000,13.000000,14.000000,83.333333,0,0,JVM,OS,8"));

        runScript(inputDir, outputDir);

        try (var files = Files.list(outputDir)) {
            assertEquals(16, files.count());
        }
        assertChartPairExists(outputDir, "execution-time-vs-balls");
        assertChartPairExists(outputDir, "speedup-vs-balls");
        assertChartPairExists(outputDir, "throughput-vs-balls");
        assertChartPairExists(outputDir, "scalability-elapsed-time-vs-workers");
        assertChartPairExists(outputDir, "scalability-throughput-vs-workers");
        assertChartPairExists(outputDir, "coordination-overhead-vs-workers");
        assertChartPairExists(outputDir, "gui-frame-time-vs-balls");
        assertChartPairExists(outputDir, "gui-fps-vs-balls");
        assertTrue(Files.readString(outputDir.resolve("execution-time-vs-balls.svg")).contains("<svg"));
    }

    @Test
    void scriptSupportsLegacyBenchmarkSuiteLayout() throws Exception {
        Path inputDir = tempDir.resolve("legacy-results");
        Path outputDir = tempDir.resolve("legacy-charts");
        Files.createDirectories(inputDir);

        write(inputDir.resolve("benchmark-summary.csv"), List.of(
                "implementation,balls,threads,steps,runs,meanMillis,minMillis,maxMillis,stdDevMillis,meanThroughput,meanCpuUtilizationPercent,speedup,efficiency",
                "sequential,100,1,100,1,10.000000,10.000000,10.000000,0.000000,1000.000000,55.000000,1.000000,1.000000",
                "threads,100,2,100,1,6.000000,6.000000,6.000000,0.000000,1666.666667,72.000000,1.666667,0.833333",
                "executor,100,2,100,1,7.000000,7.000000,7.000000,0.000000,1428.571429,68.000000,1.428571,0.714286"));
        write(inputDir.resolve("speedup-table.csv"), List.of(
                "balls,steps,implementation,threads,meanMillis,meanThroughput,meanCpuUtilizationPercent,sequentialMeanMillis,speedup,speedupBelowOne",
                "100,100,threads,2,6.000000,1666.666667,72.000000,10.000000,1.666667,false",
                "100,100,executor,2,7.000000,1428.571429,68.000000,10.000000,1.428571,false"));
        write(inputDir.resolve("efficiency-table.csv"), List.of(
                "balls,steps,implementation,threads,meanMillis,meanThroughput,meanCpuUtilizationPercent,sequentialMeanMillis,speedup,efficiency,efficiencyDegradation",
                "100,100,threads,2,6.000000,1666.666667,72.000000,10.000000,1.666667,0.833333,false",
                "100,100,executor,2,7.000000,1428.571429,68.000000,10.000000,1.428571,0.714286,false"));
        write(inputDir.resolve("benchmark-runs.csv"), List.of(
                "timestamp,implementation,balls,threads,steps,seed,runIndex,elapsedMillis,throughputStepsPerSec,cpuUtilizationPercent,checksum,status,failureReason,syncTimeMillis,aggregationTimeMillis,taskSubmissionTimeMillis,joinOrFutureWaitMillis,lockAcquisitions,submittedTasks",
                "2026-06-21T13:15:30Z,threads,100,2,100,1,1,6.000000,1666.666667,72.000000,11,SUCCESS,,0.600000,0.300000,0.150000,0.120000,4,8"));
        write(inputDir.resolve("gui-responsiveness.csv"), List.of(
                "timestamp,implementation,balls,threads,steps,seed,requestedUpdates,completedUpdates,elapsedMillis,meanUpdateIntervalMillis,meanUpdateLatencyMillis,maxUpdateLatencyMillis,updateRatePerSecond,meanEdtDelayMillis,maxEdtDelayMillis,delayedUpdates",
                "2026-06-21T13:15:30Z,sequential,100,1,100,1,20,20,30.000000,1.500000,2.000000,3.000000,666.666667,1.200000,2.500000,0"));
        write(inputDir.resolve("environment.csv"), List.of(
                "availableProcessors,cpuModel,physicalCores,logicalCpuCount,totalPhysicalMemoryBytes,jvmName,jvmVersion,osName,osVersion,osArch,maxMemoryBytes,totalMemoryBytes,freeMemoryBytes,processCpuTimeSupported,processCpuTimeNanos",
                "8,Test CPU,4,8,17179869184,JVM,21,Windows 11,10.0,amd64,1,1,1,true,123"));

        runScript(inputDir, outputDir);

        assertChartPairExists(outputDir, "execution-time-vs-balls");
        assertChartPairExists(outputDir, "speedup-vs-balls");
        assertChartPairExists(outputDir, "throughput-vs-balls");
        assertChartPairExists(outputDir, "scalability-elapsed-time-vs-workers");
        assertChartPairExists(outputDir, "scalability-throughput-vs-workers");
        assertChartPairExists(outputDir, "coordination-overhead-vs-workers");
        assertChartPairExists(outputDir, "gui-frame-time-vs-balls");
        assertChartPairExists(outputDir, "gui-fps-vs-balls");
        assertTrue(Files.readString(outputDir.resolve("execution-time-vs-balls.svg")).contains("<svg"));
    }

    private static void write(Path file, List<String> lines) throws IOException {
        Files.writeString(file, String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static void assertChartPairExists(Path outputDir, String stem) {
        assertTrue(Files.exists(outputDir.resolve(stem + ".png")));
        assertTrue(Files.exists(outputDir.resolve(stem + ".svg")));
    }

    private static void runScript(Path inputDir, Path outputDir) throws Exception {
        Path script = resolvePlotScript();
        ProcessBuilder builder = new ProcessBuilder(
                "python",
                script.toString(),
                "--input-dir",
                inputDir.toString(),
                "--output-dir",
                outputDir.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (var in = process.getInputStream()) {
            in.transferTo(buffer);
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new AssertionError("plot script failed: " + buffer.toString(StandardCharsets.UTF_8));
        }
    }

    private static Path resolvePlotScript() {
        Path repoRootStyle = Path.of("assignment-1", "scripts", "plot_benchmarks.py");
        if (Files.exists(repoRootStyle)) {
            return repoRootStyle.toAbsolutePath().normalize();
        }
        return Path.of("scripts", "plot_benchmarks.py").toAbsolutePath().normalize();
    }
}
