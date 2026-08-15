package pcd.poool.benchmark.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import pcd.poool.benchmark.core.RuntimeTelemetry;

/**
 * Writes runtime telemetry snapshots to CSV.
 */
public final class RuntimeTelemetryCsvWriter {

    public static final String ENVIRONMENT_FILE_NAME = "environment.csv";

    private RuntimeTelemetryCsvWriter() {
    }

    /**
     * Exports a telemetry snapshot to the configured output directory.
     *
     * @param outputDir benchmark output directory
     * @param telemetry runtime telemetry snapshot
     * @return exported file path
     * @throws IOException if writing fails
     */
    public static Path export(Path outputDir, RuntimeTelemetry telemetry) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(ENVIRONMENT_FILE_NAME);
        String content = RuntimeTelemetry.csvHeader() + System.lineSeparator() + telemetry.toCsvRow() + System.lineSeparator();
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return file;
    }
}
