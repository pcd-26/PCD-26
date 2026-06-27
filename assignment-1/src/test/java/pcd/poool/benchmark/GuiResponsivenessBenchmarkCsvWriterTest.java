package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiResponsivenessBenchmarkCsvWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesStableHeaderAndMeasurements() throws Exception {
        var rows = List.of(new GuiResponsivenessBenchmarkRunner.BenchmarkRow(
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
                8));

        var file = tempDir.resolve("raw-gui-results.csv");
        GuiResponsivenessBenchmarkCsvWriter.write(file, rows);

        var lines = Files.readAllLines(file);
        assertEquals("implementation,balls,workers,steps,seed,runIndex,avgFrameMs,p95FrameMs,maxFrameMs,avgFps,framesAbove16Ms,framesAbove33Ms,jvm,os,availableProcessors", lines.get(0));
        assertTrue(lines.get(1).startsWith("sequential,100,1,240,42,1,12.345678,15.000000,20.000000,80.000000,2,1"));
    }
}
