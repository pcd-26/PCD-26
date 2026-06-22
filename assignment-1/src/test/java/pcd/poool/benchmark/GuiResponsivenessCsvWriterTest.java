package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiResponsivenessCsvWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesStableHeaderAndMeasurements() throws Exception {
        var config = BenchmarkConfig.defaults()
                .withImplementation(BenchmarkConfig.ImplementationType.SEQUENTIAL)
                .withBalls(50)
                .withThreads(1)
                .withSteps(3)
                .withSeed(123L)
                .withGuiEnabled(true)
                .withOutputDir(tempDir);
        var result = new GuiResponsivenessResult(
                3L,
                3L,
                12.345678,
                3.0,
                4.0,
                5.0,
                250.0,
                1.0,
                2.0,
                1L);

        var file = GuiResponsivenessCsvWriter.export(config, result);

        assertEquals(tempDir.resolve(GuiResponsivenessCsvWriter.FILE_NAME), file);
        var lines = Files.readAllLines(file);
        assertEquals("timestamp,implementation,balls,threads,steps,seed,requestedUpdates,completedUpdates,elapsedMillis,meanUpdateIntervalMillis,meanUpdateLatencyMillis,maxUpdateLatencyMillis,updateRatePerSecond,meanEdtDelayMillis,maxEdtDelayMillis,delayedUpdates", lines.get(0));
        assertTrue(lines.get(1).contains("sequential,50,1,3,123,3,3,12.345678"));
    }
}
