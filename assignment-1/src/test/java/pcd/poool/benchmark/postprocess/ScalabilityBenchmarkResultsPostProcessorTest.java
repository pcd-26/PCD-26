package pcd.poool.benchmark.postprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalabilityBenchmarkResultsPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void aggregatesMeasuredRunsByImplementationAndWorkerCount() throws Exception {
        Path raw = tempDir.resolve("raw-scalability-results.csv");
        Files.writeString(raw, String.join(System.lineSeparator(), List.of(
                "implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,coordinationMs,coordinationRatio,tasksSubmitted,jvm,os,availableProcessors",
                "threads,2500,1,10,42,1,true,999.000000,10.000000,1.000000,0.001001,1,JVM,OS,8",
                "threads,2500,1,10,42,2,false,20.000000,500.000000,2.000000,0.100000,1,JVM,OS,8",
                "threads,2500,1,10,42,3,false,30.000000,333.333333,4.000000,0.133333,1,JVM,OS,8",
                "executor,2500,2,10,42,1,false,15.000000,666.666667,3.000000,0.200000,4,JVM,OS,8",
                "executor,2500,2,10,42,2,false,25.000000,400.000000,5.000000,0.200000,4,JVM,OS,8")) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        var result = ScalabilityBenchmarkResultsPostProcessor.process(raw);

        assertEquals(tempDir.resolve("aggregated-scalability-results.csv"), result.aggregatedFile());
        assertEquals(2, result.aggregatedRows().size());

        var lines = Files.readAllLines(result.aggregatedFile());
        assertEquals(3, lines.size());
        assertEquals("implementation,balls,workers,steps,seed,meanElapsedMs,medianElapsedMs,stdElapsedMs,meanThroughput,medianThroughput,stdThroughput,meanCoordinationMs,medianCoordinationMs,stdCoordinationMs,meanCoordinationRatio,medianCoordinationRatio,stdCoordinationRatio,meanTasksSubmitted", lines.get(0));

        Map<String, String[]> rows = indexRows(lines.subList(1, lines.size()));
        assertRow(rows.get("threads:1"),
                "threads", 2500, 1, 10, 42L,
                25.0, 25.0, 5.0, 416.666667, 416.666667, 83.333334,
                3.0, 3.0, 1.0, 0.116667, 0.116667, 0.016667, 1.0);
        assertRow(rows.get("executor:2"),
                "executor", 2500, 2, 10, 42L,
                20.0, 20.0, 5.0, 533.333334, 533.333334, 133.333334,
                4.0, 4.0, 1.0, 0.200000, 0.200000, 0.000000, 4.0);
    }

    private static Map<String, String[]> indexRows(List<String> rows) {
        Map<String, String[]> indexed = new LinkedHashMap<>();
        for (String row : rows) {
            String[] cells = row.split(",", -1);
            indexed.put(cells[0] + ":" + cells[2], cells);
        }
        return indexed;
    }

    private static void assertRow(
            String[] row,
            String implementation,
            int balls,
            int workers,
            int steps,
            long seed,
            double meanElapsedMs,
            double medianElapsedMs,
            double stdElapsedMs,
            double meanThroughput,
            double medianThroughput,
            double stdThroughput,
            double meanCoordinationMs,
            double medianCoordinationMs,
            double stdCoordinationMs,
            double meanCoordinationRatio,
            double medianCoordinationRatio,
            double stdCoordinationRatio,
            double meanTasksSubmitted) {
        assertTrue(row != null);
        assertEquals(implementation, row[0]);
        assertEquals(Integer.toString(balls), row[1]);
        assertEquals(Integer.toString(workers), row[2]);
        assertEquals(Integer.toString(steps), row[3]);
        assertEquals(Long.toString(seed), row[4]);
        assertEquals(meanElapsedMs, Double.parseDouble(row[5]), 1e-6);
        assertEquals(medianElapsedMs, Double.parseDouble(row[6]), 1e-6);
        assertEquals(stdElapsedMs, Double.parseDouble(row[7]), 1e-6);
        assertEquals(meanThroughput, Double.parseDouble(row[8]), 1e-6);
        assertEquals(medianThroughput, Double.parseDouble(row[9]), 1e-6);
        assertEquals(stdThroughput, Double.parseDouble(row[10]), 1e-6);
        assertEquals(meanCoordinationMs, Double.parseDouble(row[11]), 1e-6);
        assertEquals(medianCoordinationMs, Double.parseDouble(row[12]), 1e-6);
        assertEquals(stdCoordinationMs, Double.parseDouble(row[13]), 1e-6);
        assertEquals(meanCoordinationRatio, Double.parseDouble(row[14]), 1e-6);
        assertEquals(medianCoordinationRatio, Double.parseDouble(row[15]), 1e-6);
        assertEquals(stdCoordinationRatio, Double.parseDouble(row[16]), 1e-6);
        assertEquals(meanTasksSubmitted, Double.parseDouble(row[17]), 1e-6);
    }
}
