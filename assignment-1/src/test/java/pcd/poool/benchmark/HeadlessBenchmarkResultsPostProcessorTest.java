package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessBenchmarkResultsPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void derivesAggregatedAndSpeedupCsvFromRawResults() throws Exception {
        Path raw = tempDir.resolve("raw-results.csv");
        Files.writeString(raw, String.join(System.lineSeparator(), List.of(
                "implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,stateHash,jvm,os,availableProcessors",
                "sequential,100,1,10,42,1,false,50.000000,200.000000,11,JVM,OS,8",
                "sequential,100,1,10,42,2,false,100.000000,100.000000,11,JVM,OS,8",
                "threads,100,2,10,42,1,false,25.000000,400.000000,22,JVM,OS,8",
                "threads,100,2,10,42,2,false,40.000000,250.000000,22,JVM,OS,8",
                "executor,100,2,10,42,1,false,20.000000,500.000000,33,JVM,OS,8",
                "executor,100,2,10,42,2,false,30.000000,333.333333,33,JVM,OS,8")) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        var result = HeadlessBenchmarkResultsPostProcessor.process(raw);

        assertEquals(tempDir.resolve("aggregated-results.csv"), result.aggregatedFile());
        assertEquals(tempDir.resolve("speedup-results.csv"), result.speedupFile());
        assertEquals(3, result.aggregatedRows().size());
        assertEquals(2, result.speedupRows().size());

        var aggregatedLines = Files.readAllLines(result.aggregatedFile());
        var speedupLines = Files.readAllLines(result.speedupFile());

        assertEquals("implementation,balls,workers,steps,seed,avgElapsedMs,stdElapsedMs,avgThroughput,stdThroughput", aggregatedLines.get(0));
        assertEquals("balls,workers,implementation,avgSequentialMs,avgParallelMs,speedup", speedupLines.get(0));
        assertTrue(aggregatedLines.stream().anyMatch(line -> line.startsWith("sequential,100,1,10,42,75.000000,25.000000,150.000000,50.000000")));
        assertTrue(aggregatedLines.stream().anyMatch(line -> line.startsWith("threads,100,2,10,42,32.500000,7.500000,325.000000,75.000000")));
        assertTrue(aggregatedLines.stream().anyMatch(line -> line.startsWith("executor,100,2,10,42,25.000000,5.000000,416.666667")));
        assertTrue(speedupLines.stream().anyMatch(line -> line.startsWith("100,2,threads,75.000000,32.500000,2.307692")));
        assertTrue(speedupLines.stream().anyMatch(line -> line.startsWith("100,2,executor,75.000000,25.000000,3.000000")));
    }

    @Test
    void reportsAclearErrorWhenSequentialBaselineIsMissing() throws Exception {
        Path raw = tempDir.resolve("raw-results.csv");
        Files.writeString(raw, String.join(System.lineSeparator(), List.of(
                "implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,stateHash,jvm,os,availableProcessors",
                "threads,100,2,10,42,1,false,25.000000,400.000000,22,JVM,OS,8")) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        var ex = assertThrows(IllegalStateException.class, () -> HeadlessBenchmarkResultsPostProcessor.process(raw));
        assertTrue(ex.getMessage().contains("missing sequential baseline"));
    }
}
