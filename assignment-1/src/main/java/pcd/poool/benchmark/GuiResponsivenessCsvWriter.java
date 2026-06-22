package pcd.poool.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Writes GUI responsiveness benchmark results to CSV.
 */
public final class GuiResponsivenessCsvWriter {

    public static final String FILE_NAME = "gui-responsiveness.csv";

    private static final String HEADER =
            "timestamp,implementation,balls,threads,steps,seed,requestedUpdates,completedUpdates,elapsedMillis,meanUpdateIntervalMillis,meanUpdateLatencyMillis,maxUpdateLatencyMillis,updateRatePerSecond,meanEdtDelayMillis,maxEdtDelayMillis,delayedUpdates";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private GuiResponsivenessCsvWriter() {
    }

    /**
     * Exports one GUI responsiveness result to CSV.
     *
     * @param config benchmark configuration
     * @param result responsiveness measurements
     * @return path of the generated CSV file
     * @throws IOException if writing fails
     */
    public static Path export(BenchmarkConfig config, GuiResponsivenessResult result) throws IOException {
        Path outputDir = Files.createDirectories(config.outputDir());
        Path file = outputDir.resolve(FILE_NAME);
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());

        var content = new StringBuilder();
        if (Files.notExists(file)) {
            content.append(HEADER).append(System.lineSeparator());
        }
        content.append(csvRow(
                        timestamp,
                        config.implementation().name().toLowerCase(Locale.ROOT),
                        Integer.toString(config.balls()),
                        Integer.toString(config.threads()),
                        Integer.toString(config.steps()),
                        Long.toString(config.seed()),
                        Long.toString(result.requestedUpdates()),
                        Long.toString(result.completedUpdates()),
                        formatDouble(result.elapsedMillis()),
                        formatDouble(result.meanUpdateIntervalMillis()),
                        formatDouble(result.meanUpdateLatencyMillis()),
                        formatDouble(result.maxUpdateLatencyMillis()),
                        formatDouble(result.updateRatePerSecond()),
                        formatDouble(result.meanEdtDelayMillis()),
                        formatDouble(result.maxEdtDelayMillis()),
                        Long.toString(result.delayedUpdates())))
                .append(System.lineSeparator());
        if (Files.exists(file)) {
            Files.writeString(file, content.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } else {
            Files.writeString(file, content.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
        return file;
    }

    private static String csvRow(String... values) {
        var row = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                row.append(',');
            }
            row.append(escape(values[i]));
        }
        return row.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needsQuotes) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String formatDouble(double value) {
        if (Double.isNaN(value)) {
            return "";
        }
        return String.format(Locale.US, "%.6f", value);
    }
}
