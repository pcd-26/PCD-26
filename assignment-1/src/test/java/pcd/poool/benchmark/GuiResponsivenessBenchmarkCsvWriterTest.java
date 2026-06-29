package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiResponsivenessBenchmarkCsvWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsRowsWithoutDuplicatingTheHeader() throws Exception {
        var firstRow = new GuiResponsivenessBenchmarkRunner.BenchmarkRow(
                "sequential",
                100,
                1,
                240,
                42L,
                1,
                12.345678,
                15.000000,
                20.000000,
                80.000000,
                2L,
                1L,
                "JVM",
                "OS",
                8);
        var secondRow = new GuiResponsivenessBenchmarkRunner.BenchmarkRow(
                "threads",
                500,
                4,
                240,
                42L,
                2,
                10.000000,
                11.000000,
                12.000000,
                90.000000,
                0L,
                0L,
                "JVM",
                "OS",
                8);

        var file = tempDir.resolve("raw-gui-results.csv");
        GuiResponsivenessBenchmarkCsvWriter.initialize(file);
        GuiResponsivenessBenchmarkCsvWriter.append(file, firstRow);
        GuiResponsivenessBenchmarkCsvWriter.append(file, secondRow);

        var lines = Files.readAllLines(file);
        assertEquals("implementation,balls,workers,steps,seed,runIndex,avgFrameMs,p95FrameMs,maxFrameMs,avgFps,framesAbove16Ms,framesAbove33Ms,jvm,os,availableProcessors", lines.get(0));
        assertTrue(lines.get(1).startsWith("sequential,100,1,240,42,1,12.345678,15.000000,20.000000,80.000000,2,1"));
        assertTrue(lines.get(2).startsWith("threads,500,4,240,42,2,10.000000,11.000000,12.000000,90.000000,0,0"));
    }
}
