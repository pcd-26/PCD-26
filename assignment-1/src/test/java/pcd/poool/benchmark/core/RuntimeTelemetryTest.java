package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeTelemetryTest {

    @TempDir
    Path tempDir;

    @Test
    void captureCollectsRuntimeAndEnvironmentMetadata() {
        var telemetry = RuntimeTelemetry.capture();

        assertEquals(Runtime.getRuntime().availableProcessors(), telemetry.maxThreads());
        assertNotNull(telemetry.jvmName());
        assertNotNull(telemetry.jvmVersion());
        assertNotNull(telemetry.osName());
        assertNotNull(telemetry.osVersion());
        assertNotNull(telemetry.osArch());
    }

    @Test
    void csvExportUsesStableHeader() throws Exception {
        var telemetry = RuntimeTelemetry.capture();
        var file = RuntimeTelemetryCsvWriter.export(tempDir, telemetry);

        assertEquals(tempDir.resolve(RuntimeTelemetryCsvWriter.ENVIRONMENT_FILE_NAME), file);
        var lines = Files.readAllLines(file);
        assertEquals(RuntimeTelemetry.csvHeader(), lines.get(0));
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains(String.valueOf(telemetry.maxThreads())));
        assertTrue(lines.get(1).contains(telemetry.jvmName()));
    }
}
