package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeadlessBenchmarkCsvWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsRowsWithoutDuplicatingTheHeader() throws Exception {
        var file = tempDir.resolve("raw-results.csv");
        HeadlessBenchmarkCsvWriter.initialize(file);
        HeadlessBenchmarkCsvWriter.append(file, new HeadlessBenchmarkRunner.BenchmarkRow(
                "sequential",
                100,
                1,
                1_000,
                42L,
                1,
                false,
                12.345678,
                81.000000,
                0.0,
                0.0,
                0L,
                11L,
                "JVM",
                "OS",
                8));
        HeadlessBenchmarkCsvWriter.append(file, new HeadlessBenchmarkRunner.BenchmarkRow(
                "threads",
                100,
                4,
                1_000,
                42L,
                2,
                false,
                10.000000,
                100.000000,
                1.000000,
                0.100000,
                2L,
                12L,
                "JVM",
                "OS",
                8));

        var lines = Files.readAllLines(file);
        assertEquals("implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,coordinationMs,coordinationRatio,tasksSubmitted,stateHash,jvm,os,availableProcessors", lines.get(0));
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).startsWith("sequential,100,1,1000,42,1,false,12.345678,81.000000"));
        assertTrue(lines.get(2).startsWith("threads,100,4,1000,42,2,false,10.000000,100.000000"));
    }
}
