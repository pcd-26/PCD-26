package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        write(inputDir.resolve(BenchmarkCsvWriter.SUMMARY_FILE_NAME), List.of(
                "implementation,balls,threads,steps,runs,meanMillis,minMillis,maxMillis,stdDevMillis,meanThroughput,meanCpuUtilizationPercent,speedup,efficiency",
                "sequential,100,1,100,5,10.000000,10.000000,10.000000,0.000000,1000.000000,55.000000,1.000000,1.000000",
                "threads,100,2,100,5,6.000000,6.000000,6.000000,0.000000,1666.666667,72.000000,1.666667,0.833333",
                "executor,100,2,100,5,7.000000,7.000000,7.000000,0.000000,1428.571429,68.000000,1.428571,0.714286",
                "sequential,500,1,100,5,20.000000,20.000000,20.000000,0.000000,5000.000000,58.000000,1.000000,1.000000",
                "threads,500,4,100,5,8.000000,8.000000,8.000000,0.000000,12500.000000,80.000000,2.500000,0.625000",
                "executor,500,4,100,5,9.000000,9.000000,9.000000,0.000000,11111.111111,76.000000,2.222222,0.555556",
                "sequential,1000,1,100,5,25.000000,25.000000,25.000000,0.000000,4000.000000,60.000000,1.000000,1.000000",
                "threads,1000,4,100,5,11.000000,11.000000,11.000000,0.000000,9090.909091,74.000000,2.272727,0.568182",
                "executor,1000,4,100,5,12.000000,12.000000,12.000000,0.000000,8333.333333,70.000000,2.083333,0.520833",
                "sequential,2000,1,100,5,40.000000,40.000000,40.000000,0.000000,2500.000000,62.000000,1.000000,1.000000",
                "threads,2000,8,100,5,18.000000,18.000000,18.000000,0.000000,5555.555556,82.000000,2.222222,0.277778",
                "executor,2000,8,100,5,19.000000,19.000000,19.000000,0.000000,5263.157895,79.000000,2.105263,0.263158",
                "sequential,5000,1,100,5,90.000000,90.000000,90.000000,0.000000,1111.111111,65.000000,1.000000,1.000000",
                "threads,5000,8,100,5,42.000000,42.000000,42.000000,0.000000,2380.952381,88.000000,2.142857,0.267857",
                "executor,5000,8,100,5,40.000000,40.000000,40.000000,0.000000,2500.000000,86.000000,2.250000,0.281250"));

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
                "2026-06-21T13:15:32Z,executor,100,2,100,1,1,false,7.000000,1428.571429,68.000000,11,SUCCESS,,0.650000,0.320000,0.160000,0.130000,5,10",
                "2026-06-21T13:15:33Z,sequential,500,1,100,1,1,false,20.000000,5000.000000,58.000000,11,SUCCESS,,0.800000,0.300000,0.200000,0.150000,3,6",
                "2026-06-21T13:15:34Z,threads,500,4,100,1,1,false,8.000000,12500.000000,80.000000,11,SUCCESS,,1.100000,0.450000,0.250000,0.200000,6,12",
                "2026-06-21T13:15:35Z,executor,500,4,100,1,1,false,9.000000,11111.111111,76.000000,11,SUCCESS,,1.000000,0.400000,0.220000,0.180000,5,10",
                "2026-06-21T13:15:36Z,sequential,1000,1,100,1,1,false,25.000000,4000.000000,60.000000,11,SUCCESS,,0.900000,0.320000,0.220000,0.160000,3,6",
                "2026-06-21T13:15:37Z,threads,1000,4,100,1,1,false,11.000000,9090.909091,74.000000,11,SUCCESS,,1.200000,0.500000,0.270000,0.210000,6,12",
                "2026-06-21T13:15:38Z,executor,1000,4,100,1,1,false,12.000000,8333.333333,70.000000,11,SUCCESS,,1.150000,0.480000,0.260000,0.200000,5,10",
                "2026-06-21T13:15:39Z,sequential,2000,1,100,1,1,false,40.000000,2500.000000,62.000000,11,SUCCESS,,1.000000,0.350000,0.250000,0.180000,3,6",
                "2026-06-21T13:15:40Z,threads,2000,8,100,1,1,false,18.000000,5555.555556,82.000000,11,SUCCESS,,1.500000,0.600000,0.350000,0.280000,8,16",
                "2026-06-21T13:15:41Z,executor,2000,8,100,1,1,false,19.000000,5263.157895,79.000000,11,SUCCESS,,1.450000,0.590000,0.330000,0.260000,7,14",
                "2026-06-21T13:15:42Z,sequential,5000,1,100,1,1,false,90.000000,1111.111111,65.000000,11,SUCCESS,,1.200000,0.400000,0.300000,0.220000,3,6",
                "2026-06-21T13:15:43Z,threads,5000,8,100,1,1,false,42.000000,2380.952381,88.000000,11,SUCCESS,,2.000000,0.750000,0.400000,0.320000,8,16",
                "2026-06-21T13:15:44Z,executor,5000,8,100,1,1,false,40.000000,2500.000000,86.000000,11,SUCCESS,,1.900000,0.720000,0.390000,0.310000,7,14"));

        write(inputDir.resolve("gui-responsiveness.csv"), List.of(
                "timestamp,implementation,balls,threads,steps,seed,requestedUpdates,completedUpdates,elapsedMillis,meanUpdateIntervalMillis,meanUpdateLatencyMillis,maxUpdateLatencyMillis,updateRatePerSecond,meanEdtDelayMillis,maxEdtDelayMillis,delayedUpdates",
                "2026-06-21T13:15:30Z,sequential,100,1,100,1,20,20,30.000000,1.500000,2.000000,3.000000,666.666667,1.200000,2.500000,0",
                "2026-06-21T13:15:31Z,threads,100,2,100,1,20,20,25.000000,1.250000,1.500000,2.000000,800.000000,1.000000,2.000000,0",
                "2026-06-21T13:15:32Z,executor,100,2,100,1,20,20,22.000000,1.100000,1.300000,1.900000,909.090909,0.900000,1.800000,0",
                "2026-06-21T13:15:33Z,sequential,500,1,100,1,20,20,34.000000,1.700000,2.100000,3.400000,588.235294,1.300000,2.700000,0",
                "2026-06-21T13:15:34Z,threads,500,4,100,1,20,20,26.000000,1.300000,1.600000,2.100000,769.230769,1.050000,2.100000,0",
                "2026-06-21T13:15:35Z,executor,500,4,100,1,20,20,24.000000,1.200000,1.400000,2.000000,833.333333,0.950000,1.900000,0",
                "2026-06-21T13:15:36Z,sequential,1000,1,100,1,20,20,40.000000,2.000000,2.600000,3.900000,500.000000,1.400000,2.800000,0",
                "2026-06-21T13:15:37Z,threads,1000,4,100,1,20,20,28.000000,1.400000,1.700000,2.300000,714.285714,1.100000,2.200000,0",
                "2026-06-21T13:15:38Z,executor,1000,4,100,1,20,20,27.000000,1.350000,1.500000,2.200000,740.740741,1.000000,2.000000,0",
                "2026-06-21T13:15:39Z,sequential,2000,1,100,1,20,20,48.000000,2.400000,3.100000,4.500000,416.666667,1.700000,3.400000,0",
                "2026-06-21T13:15:40Z,threads,2000,8,100,1,20,20,30.000000,1.500000,1.800000,2.500000,666.666667,1.200000,2.300000,0",
                "2026-06-21T13:15:41Z,executor,2000,8,100,1,20,20,29.000000,1.450000,1.700000,2.400000,689.655172,1.150000,2.200000,0",
                "2026-06-21T13:15:42Z,sequential,5000,1,100,1,20,20,60.000000,3.000000,3.800000,5.200000,333.333333,2.000000,4.000000,0",
                "2026-06-21T13:15:43Z,threads,5000,8,100,1,20,20,36.000000,1.800000,2.000000,2.900000,555.555556,1.300000,2.600000,0",
                "2026-06-21T13:15:44Z,executor,5000,8,100,1,20,20,35.000000,1.750000,1.900000,2.800000,571.428571,1.250000,2.500000,0"));

        runScript(inputDir, outputDir);

        try (var files = Files.list(outputDir)) {
            assertEquals(14, files.count());
        }
        assertChartPairExists(outputDir, "01_best_execution_time_vs_balls");
        assertChartPairExists(outputDir, "02_best_throughput_vs_balls");
        assertChartPairExists(outputDir, "03_speedup_vs_thread_count");
        assertChartPairExists(outputDir, "04_efficiency_vs_thread_count");
        assertChartPairExists(outputDir, "05_coordination_overhead_vs_thread_count");
        assertChartPairExists(outputDir, "06_cpu_utilization_vs_thread_count");
        assertChartPairExists(outputDir, "07_gui_latency_vs_balls");
    }

    @Test
    void scriptSkipsGuiChartWhenGuiDataIsIncomplete() throws Exception {
        Path inputDir = tempDir.resolve("results");
        Path outputDir = tempDir.resolve("charts").resolve("report");
        Files.createDirectories(inputDir);

        write(inputDir.resolve(BenchmarkCsvWriter.SUMMARY_FILE_NAME), List.of(
                "implementation,balls,threads,steps,runs,meanMillis,minMillis,maxMillis,stdDevMillis,meanThroughput,meanCpuUtilizationPercent,speedup,efficiency",
                "sequential,100,1,100,5,10.000000,10.000000,10.000000,0.000000,1000.000000,55.000000,1.000000,1.000000",
                "threads,100,2,100,5,6.000000,6.000000,6.000000,0.000000,1666.666667,72.000000,1.666667,0.833333",
                "executor,100,2,100,5,7.000000,7.000000,7.000000,0.000000,1428.571429,68.000000,1.428571,0.714286"));

        write(inputDir.resolve(BenchmarkScalabilityAnalyzer.SPEEDUP_TABLE_FILE_NAME), List.of(
                "balls,steps,implementation,threads,meanMillis,meanThroughput,meanCpuUtilizationPercent,sequentialMeanMillis,speedup,speedupBelowOne",
                "100,100,sequential,1,10.000000,1000.000000,55.000000,10.000000,1.000000,false",
                "100,100,threads,2,6.000000,1666.666667,72.000000,10.000000,1.666667,false",
                "100,100,executor,2,7.000000,1428.571429,68.000000,10.000000,1.428571,false"));

        write(inputDir.resolve(BenchmarkScalabilityAnalyzer.EFFICIENCY_TABLE_FILE_NAME), List.of(
                "balls,steps,implementation,threads,meanMillis,meanThroughput,meanCpuUtilizationPercent,sequentialMeanMillis,speedup,efficiency,efficiencyDegradation",
                "100,100,sequential,1,10.000000,1000.000000,55.000000,10.000000,1.000000,1.000000,false",
                "100,100,threads,2,6.000000,1666.666667,72.000000,10.000000,1.666667,0.833333,false",
                "100,100,executor,2,7.000000,1428.571429,68.000000,10.000000,1.428571,0.714286,false"));

        write(inputDir.resolve(BenchmarkCsvWriter.RUNS_FILE_NAME), List.of(
                "timestamp,implementation,balls,threads,steps,seed,runIndex,warmup,elapsedMillis,throughputStepsPerSec,cpuUtilizationPercent,checksum,status,failureReason,syncTimeMillis,aggregationTimeMillis,taskSubmissionTimeMillis,joinOrFutureWaitMillis,lockAcquisitions,submittedTasks",
                "2026-06-21T13:15:30Z,sequential,100,1,100,1,1,false,10.000000,1000.000000,55.000000,11,SUCCESS,,0.500000,0.200000,0.100000,0.100000,2,4",
                "2026-06-21T13:15:31Z,threads,100,2,100,1,1,false,6.000000,1666.666667,72.000000,11,SUCCESS,,0.600000,0.300000,0.150000,0.120000,4,8",
                "2026-06-21T13:15:32Z,executor,100,2,100,1,1,false,7.000000,1428.571429,68.000000,11,SUCCESS,,0.650000,0.320000,0.160000,0.130000,5,10"));

        write(inputDir.resolve("gui-responsiveness.csv"), List.of(
                "timestamp,implementation,balls,threads,steps,seed,requestedUpdates,completedUpdates,elapsedMillis,meanUpdateIntervalMillis,meanUpdateLatencyMillis,maxUpdateLatencyMillis,updateRatePerSecond,meanEdtDelayMillis,maxEdtDelayMillis,delayedUpdates",
                "2026-06-21T13:15:30Z,sequential,100,1,100,1,20,20,30.000000,1.500000,2.000000,3.000000,666.666667,1.200000,2.500000,0",
                "2026-06-21T13:15:31Z,threads,100,2,100,1,20,20,25.000000,1.250000,1.500000,2.000000,800.000000,1.000000,2.000000,0"));

        runScript(inputDir, outputDir);

        assertTrue(Files.exists(outputDir.resolve("01_best_execution_time_vs_balls.png")));
        assertTrue(Files.exists(outputDir.resolve("06_cpu_utilization_vs_thread_count.svg")));
        assertFalse(Files.exists(outputDir.resolve("07_gui_latency_vs_balls.png")));
        assertFalse(Files.exists(outputDir.resolve("07_gui_latency_vs_balls.svg")));
    }

    private static void write(Path file, List<String> lines) throws IOException {
        Files.writeString(file, String.join(System.lineSeparator(), lines), StandardCharsets.UTF_8);
    }

    private static void assertChartPairExists(Path outputDir, String stem) {
        assertTrue(Files.exists(outputDir.resolve(stem + ".png")));
        assertTrue(Files.exists(outputDir.resolve(stem + ".svg")));
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
