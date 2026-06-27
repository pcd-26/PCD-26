package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalabilityBenchmarkResultsPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void aggregatesMeasuredRunsByImplementationAndWorkerCount() throws Exception {
        Path raw = tempDir.resolve("raw-scalability-results.csv");
        Files.writeString(raw, String.join(System.lineSeparator(), List.of(
                "implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,jvm,os,availableProcessors",
                "threads,2500,1,10,42,1,true,999.000000,10.000000,JVM,OS,8",
                "threads,2500,1,10,42,2,false,20.000000,500.000000,JVM,OS,8",
                "threads,2500,1,10,42,3,false,30.000000,333.333333,JVM,OS,8",
                "executor,2500,2,10,42,1,false,15.000000,666.666667,JVM,OS,8",
                "executor,2500,2,10,42,2,false,25.000000,400.000000,JVM,OS,8")) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        var result = ScalabilityBenchmarkResultsPostProcessor.process(raw);

        assertEquals(tempDir.resolve("aggregated-scalability-results.csv"), result.aggregatedFile());
        assertEquals(2, result.aggregatedRows().size());

        var lines = Files.readAllLines(result.aggregatedFile());
        assertEquals("implementation,balls,workers,steps,seed,avgElapsedMs,stdElapsedMs,avgThroughput,stdThroughput", lines.get(0));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("threads,2500,1,10,42,25.000000,5.000000,416.666667")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("executor,2500,2,10,42,20.000000,5.000000,533.333334")));
    }
}
