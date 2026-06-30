package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkScalabilityAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzeBuildsReportReadyScalabilityTables() throws Exception {
        Path inputDir = tempDir.resolve("results");
        Files.createDirectories(inputDir);
        Files.writeString(inputDir.resolve(BenchmarkCsvWriter.SUMMARY_FILE_NAME), String.join(System.lineSeparator(),
                "implementation,balls,threads,steps,runs,meanMillis,medianMillis,minMillis,maxMillis,stdDevMillis,meanThroughput,medianThroughput,meanCpuUtilizationPercent,medianCpuUtilizationPercent,speedup,efficiency",
                "sequential,100,1,100,5,10.000000,10.000000,10.000000,10.000000,0.000000,1000.000000,1000.000000,50.000000,50.000000,1.000000,1.000000",
                "threads,100,1,100,5,12.000000,12.000000,12.000000,12.000000,0.000000,833.333333,833.333333,55.000000,55.000000,0.833333,0.833333",
                "threads,100,2,100,5,6.250000,6.250000,6.250000,6.250000,0.000000,1600.000000,1600.000000,65.000000,65.000000,1.600000,0.800000",
                "threads,100,4,100,5,6.670000,6.670000,6.670000,6.670000,0.000000,1499.250375,1499.250375,70.000000,70.000000,1.499250,0.374813",
                "executor,100,1,100,5,13.330000,13.330000,13.330000,13.330000,0.000000,750.187547,750.187547,52.000000,52.000000,0.750188,0.750188",
                "executor,100,2,100,5,7.140000,7.140000,7.140000,7.140000,0.000000,1400.560224,1400.560224,60.000000,60.000000,1.400560,0.700280",
                "executor,100,4,100,5,7.690000,7.690000,7.690000,7.690000,0.000000,1300.390117,1300.390117,62.000000,62.000000,1.300390,0.325098",
                "sequential,500,1,100,5,20.000000,20.000000,20.000000,20.000000,0.000000,5000.000000,5000.000000,48.000000,48.000000,1.000000,1.000000",
                "threads,500,1,100,5,22.000000,22.000000,22.000000,22.000000,0.000000,4545.454545,4545.454545,49.000000,49.000000,0.909091,0.909091",
                "threads,500,2,100,5,8.000000,8.000000,8.000000,8.000000,0.000000,12500.000000,12500.000000,68.000000,68.000000,2.500000,1.250000",
                "threads,500,4,100,5,10.000000,10.000000,10.000000,10.000000,0.000000,10000.000000,10000.000000,71.000000,71.000000,2.000000,0.500000",
                "executor,500,1,100,5,24.000000,24.000000,24.000000,24.000000,0.000000,4166.666667,4166.666667,47.000000,47.000000,0.833333,0.833333",
                "executor,500,2,100,5,9.000000,9.000000,9.000000,9.000000,0.000000,11111.111111,11111.111111,66.000000,66.000000,2.222222,1.111111",
                "executor,500,4,100,5,12.000000,12.000000,12.000000,12.000000,0.000000,8333.333333,8333.333333,69.000000,69.000000,1.666667,0.416667"),
                java.nio.charset.StandardCharsets.UTF_8);

        var report = BenchmarkScalabilityAnalyzer.analyze(inputDir, tempDir.resolve("analysis"));

        assertEquals(14, report.summaryRows());
        assertEquals(14, report.speedupRows());
        assertEquals(14, report.efficiencyRows());
        assertEquals(2, report.scalabilityRows());
        assertTrue(Files.exists(tempDir.resolve("analysis").resolve(BenchmarkScalabilityAnalyzer.SPEEDUP_TABLE_FILE_NAME)));
        assertTrue(Files.exists(tempDir.resolve("analysis").resolve(BenchmarkScalabilityAnalyzer.EFFICIENCY_TABLE_FILE_NAME)));
        assertTrue(Files.exists(tempDir.resolve("analysis").resolve(BenchmarkScalabilityAnalyzer.SCALABILITY_TABLE_FILE_NAME)));

        List<String> speedupLines = Files.readAllLines(tempDir.resolve("analysis").resolve(BenchmarkScalabilityAnalyzer.SPEEDUP_TABLE_FILE_NAME));
        List<String> efficiencyLines = Files.readAllLines(tempDir.resolve("analysis").resolve(BenchmarkScalabilityAnalyzer.EFFICIENCY_TABLE_FILE_NAME));
        List<String> scalabilityLines = Files.readAllLines(tempDir.resolve("analysis").resolve(BenchmarkScalabilityAnalyzer.SCALABILITY_TABLE_FILE_NAME));

        assertEquals("balls,steps,implementation,threads,meanMillis,medianMillis,meanThroughput,medianThroughput,meanCpuUtilizationPercent,medianCpuUtilizationPercent,sequentialMedianMillis,speedup,speedupBelowOne", speedupLines.get(0));
        assertEquals("balls,steps,implementation,threads,meanMillis,medianMillis,meanThroughput,medianThroughput,meanCpuUtilizationPercent,medianCpuUtilizationPercent,sequentialMedianMillis,speedup,efficiency,efficiencyDegradation", efficiencyLines.get(0));
        assertEquals("balls,steps,sequentialThroughput,threadedBestThreads,threadedBestThroughput,threadedCpuUtilization,threadedSpeedup,threadedEfficiency,threadedSaturationPoint,threadedSlowerThanSequential,threadedEfficiencyDegradation,executorBestThreads,executorBestThroughput,executorCpuUtilization,executorSpeedup,executorEfficiency,executorSaturationPoint,executorSlowerThanThreaded,executorEfficiencyDegradation", scalabilityLines.get(0));

        assertTrue(speedupLines.stream().anyMatch(line -> line.startsWith("100,100,threads,1,12.000000,12.000000,833.333333,833.333333,55.000000,55.000000,10.000000,0.833333,true")));
        assertTrue(efficiencyLines.stream().anyMatch(line -> line.startsWith("100,100,threads,4,6.670000,6.670000,1499.250375,1499.250375,70.000000,70.000000,10.000000,1.499250,0.374813,true")));
        assertTrue(scalabilityLines.stream().anyMatch(line -> line.startsWith("100,100,1000.000000,2,1600.000000,65.000000,1.600000,0.800000,2,false,true,2,1400.560224,60.000000,1.400560,0.700280,2,true,true")));
        assertTrue(scalabilityLines.stream().anyMatch(line -> line.startsWith("500,100,5000.000000,2,12500.000000,68.000000,2.500000,1.250000,2,false,true,2,11111.111111,66.000000,2.222222,1.111111,2,true,true")));
    }
}
