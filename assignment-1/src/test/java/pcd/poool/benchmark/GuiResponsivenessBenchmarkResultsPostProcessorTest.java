package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiResponsivenessBenchmarkResultsPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void aggregatesMeasuredRunsByImplementationAndWorkload() throws Exception {
        Path raw = tempDir.resolve("raw-gui-results.csv");
        Files.writeString(raw, String.join(System.lineSeparator(), List.of(
                "implementation,balls,workers,steps,seed,runIndex,avgFrameMs,p95FrameMs,maxFrameMs,avgFps,framesAbove16Ms,framesAbove33Ms,jvm,os,availableProcessors",
                "sequential,100,1,240,42,1,10.000000,12.000000,14.000000,100.000000,0,0,JVM,OS,8",
                "sequential,100,1,240,42,2,20.000000,22.000000,24.000000,50.000000,1,0,JVM,OS,8",
                "threads,100,2,240,42,1,8.000000,9.000000,10.000000,120.000000,0,0,JVM,OS,8",
                "threads,100,2,240,42,2,12.000000,15.000000,16.000000,80.000000,1,0,JVM,OS,8",
                "executor,100,2,240,42,1,6.000000,7.000000,8.000000,140.000000,0,0,JVM,OS,8",
                "executor,100,2,240,42,2,10.000000,11.000000,12.000000,90.000000,0,1,JVM,OS,8")) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        var result = GuiResponsivenessBenchmarkResultsPostProcessor.process(raw);

        assertEquals(tempDir.resolve("aggregated-gui-results.csv"), result.aggregatedFile());
        assertEquals(3, result.aggregatedRows().size());

        var lines = Files.readAllLines(result.aggregatedFile());
        assertEquals("implementation,balls,workers,steps,seed,meanFrameMs,medianFrameMs,p95FrameMs,maxFrameMs,meanFps,medianFps,meanFramesAbove16Ms,meanFramesAbove33Ms", lines.get(0));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("sequential,100,1,240,42,15.000000,15.000000,17.000000,19.000000,75.000000,75.000000,0.500000,0.000000")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("threads,100,2,240,42,10.000000,10.000000,12.000000,13.000000,100.000000,100.000000,0.500000,0.000000")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("executor,100,2,240,42,8.000000,8.000000,9.000000,10.000000,115.000000,115.000000,0.000000,0.500000")));
    }
}
