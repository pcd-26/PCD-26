package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalabilityBenchmarkCsvWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsRowsWithoutDuplicatingTheHeader() throws Exception {
        var file = tempDir.resolve("raw-scalability-results.csv");
        ScalabilityBenchmarkCsvWriter.initialize(file);
        ScalabilityBenchmarkCsvWriter.append(file, new ScalabilityBenchmarkRunner.BenchmarkRow(
                "threads",
                2_500,
                2,
                1_000,
                42L,
                1,
                false,
                12.345678,
                81.000000,
                1.000000,
                0.100000,
                2L,
                "JVM",
                "OS",
                8));
        ScalabilityBenchmarkCsvWriter.append(file, new ScalabilityBenchmarkRunner.BenchmarkRow(
                "executor",
                2_500,
                4,
                1_000,
                42L,
                2,
                false,
                10.000000,
                100.000000,
                2.000000,
                0.200000,
                4L,
                "JVM",
                "OS",
                8));

        var lines = Files.readAllLines(file);
        assertEquals("implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,coordinationMs,coordinationRatio,tasksSubmitted,jvm,os,availableProcessors", lines.get(0));
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).startsWith("threads,2500,2,1000,42,1,false,12.345678,81.000000"));
        assertTrue(lines.get(2).startsWith("executor,2500,4,1000,42,2,false,10.000000,100.000000"));
    }
}
