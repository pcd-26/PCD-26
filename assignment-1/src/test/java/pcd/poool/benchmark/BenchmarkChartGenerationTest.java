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
        Path outputDir = tempDir.resolve("charts");
        Files.createDirectories(inputDir);

        write(inputDir.resolve(BenchmarkCsvWriter.SUMMARY_FILE_NAME), List.of(
                "implementation,balls,threads,steps,runs,meanMillis,minMillis,maxMillis,stdDevMillis,meanThroughput,meanCpuUtilizationPercent,speedup,efficiency",
                "sequential,100,1,100,5,10.000000,10.000000,10.000000,0.000000,1000.000000,55.000000,1.000000,1.000000",
                "threads,100,2,100,5,6.000000,6.000000,6.000000,0.000000,1666.666667,72.000000,1.666667,0.833333",
                "executor,100,2,100,5,7.000000,7.000000,7.000000,0.000000,1428.571429,68.000000,1.428571,0.714286",
                "sequential,500,1,100,5,20.000000,20.000000,20.000000,0.000000,5000.000000,58.000000,1.000000,1.000000",
                "threads,500,4,100,5,8.000000,8.000000,8.000000,0.000000,12500.000000,80.000000,2.500000,0.625000",
                "executor,500,4,100,5,9.000000,9.000000,9.000000,0.000000,11111.111111,76.000000,2.222222,0.555556"));

        write(inputDir.resolve(BenchmarkScalabilityAnalyzer.SPEEDUP_TABLE_FILE_NAME), List.of(
                "balls,steps,implementation,threads,meanMillis,meanThroughput,meanCpuUtilizationPercent,sequentialMeanMillis,speedup,speedupBelowOne",
                "100,100,sequential,1,10.000000,1000.000000,55.000000,10.000000,1.000000,false",
                "100,100,threads,2,6.000000,1666.666667,72.000000,10.000000,1.666667,false",
                "100,100,executor,2,7.000000,1428.571429,68.000000,10.000000,1.428571,false",
                "500,100,sequential,1,20.000000,5000.000000,58.000000,20.000000,1.000000,false",
                "500,100,threads,4,8.000000,12500.000000,80.000000,20.000000,2.500000,false",
                "500,100,executor,4,9.000000,11111.111111,76.000000,20.000000,2.222222,false"));

        write(inputDir.resolve(BenchmarkScalabilityAnalyzer.EFFICIENCY_TABLE_FILE_NAME), List.of(
                "balls,steps,implementation,threads,meanMillis,meanThroughput,meanCpuUtilizationPercent,sequentialMeanMillis,speedup,efficiency,efficiencyDegradation",
                "100,100,sequential,1,10.000000,1000.000000,55.000000,10.000000,1.000000,1.000000,false",
                "100,100,threads,2,6.000000,1666.666667,72.000000,10.000000,1.666667,0.833333,false",
                "100,100,executor,2,7.000000,1428.571429,68.000000,10.000000,1.428571,0.714286,false",
                "500,100,sequential,1,20.000000,5000.000000,58.000000,20.000000,1.000000,1.000000,false",
                "500,100,threads,4,8.000000,12500.000000,80.000000,20.000000,2.500000,0.625000,true",
                "500,100,executor,4,9.000000,11111.111111,76.000000,20.000000,2.222222,0.555556,true"));

        write(inputDir.resolve(BenchmarkCsvWriter.RUNS_FILE_NAME), List.of(
                "timestamp,implementation,balls,threads,steps,seed,runIndex,warmup,elapsedMillis,throughputStepsPerSec,cpuUtilizationPercent,checksum,status,failureReason,syncTimeMillis,aggregationTimeMillis,taskSubmissionTimeMillis,joinOrFutureWaitMillis,lockAcquisitions,submittedTasks",
                "2026-06-21T13:15:30Z,sequential,100,1,100,1,1,false,10.000000,1000.000000,55.000000,11,SUCCESS,,0.500000,0.200000,0.100000,0.100000,2,4",
                "2026-06-21T13:15:31Z,threads,100,2,100,1,1,false,6.000000,1666.666667,72.000000,11,SUCCESS,,0.600000,0.300000,0.150000,0.120000,4,8",
                "2026-06-21T13:15:32Z,executor,500,4,100,1,1,false,9.000000,11111.111111,76.000000,11,SUCCESS,,0.700000,0.350000,0.170000,0.150000,6,12"));

        write(inputDir.resolve("gui-responsiveness.csv"), List.of(
                "timestamp,implementation,balls,threads,steps,seed,requestedUpdates,completedUpdates,elapsedMillis,meanUpdateIntervalMillis,meanUpdateLatencyMillis,maxUpdateLatencyMillis,updateRatePerSecond,meanEdtDelayMillis,maxEdtDelayMillis,delayedUpdates",
                "2026-06-21T13:15:30Z,sequential,100,1,100,1,20,20,30.000000,1.500000,2.000000,3.000000,666.666667,1.200000,2.500000,0",
                "2026-06-21T13:15:31Z,threads,500,4,100,1,20,20,25.000000,1.250000,1.500000,2.000000,800.000000,1.000000,2.000000,0",
                "2026-06-21T13:15:32Z,executor,500,4,100,1,20,20,22.000000,1.100000,1.300000,1.900000,909.090909,0.900000,1.800000,0"));

        runScript(inputDir, outputDir);

        try (var files = Files.list(outputDir)) {
            assertEquals(7, files.count());
        }
        assertTrue(Files.exists(outputDir.resolve("execution-time-vs-balls.png")));
        assertTrue(Files.exists(outputDir.resolve("throughput-vs-balls.png")));
        assertTrue(Files.exists(outputDir.resolve("speedup-vs-thread-count.png")));
        assertTrue(Files.exists(outputDir.resolve("efficiency-vs-thread-count.png")));
        assertTrue(Files.exists(outputDir.resolve("cpu-utilization-vs-thread-count.png")));
        assertTrue(Files.exists(outputDir.resolve("synchronization-overhead-vs-thread-count.png")));
        assertTrue(Files.exists(outputDir.resolve("gui-latency-vs-balls.png")));
    }

    private static void write(Path file, List<String> lines) throws IOException {
        Files.writeString(file, String.join(System.lineSeparator(), lines), StandardCharsets.UTF_8);
    }

    private static void runScript(Path inputDir, Path outputDir) throws Exception {
        Path script = Path.of("..", "scripts", "plot_benchmarks.py").toAbsolutePath().normalize();
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
}
