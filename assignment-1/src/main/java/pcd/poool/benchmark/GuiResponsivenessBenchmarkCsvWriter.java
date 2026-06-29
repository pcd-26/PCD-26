package pcd.poool.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Writes the raw CSV output for the dedicated GUI benchmark.
 */
final class GuiResponsivenessBenchmarkCsvWriter {

    private static final String HEADER =
            "implementation,balls,workers,steps,seed,runIndex,avgFrameMs,p95FrameMs,maxFrameMs,avgFps,framesAbove16Ms,framesAbove33Ms,jvm,os,availableProcessors";

    private GuiResponsivenessBenchmarkCsvWriter() {
    }

    static void initialize(Path outputFile) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile");

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(
                outputFile,
                HEADER + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    static void append(Path outputFile, GuiResponsivenessBenchmarkRunner.BenchmarkRow row) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile");
        Objects.requireNonNull(row, "row");
        ensureInitialized(outputFile);
        Files.writeString(
                outputFile,
                csvRow(
                        row.implementation(),
                        Integer.toString(row.balls()),
                        Integer.toString(row.workers()),
                        Integer.toString(row.steps()),
                        Long.toString(row.seed()),
                        Integer.toString(row.runIndex()),
                        formatDouble(row.avgFrameMs()),
                        formatDouble(row.p95FrameMs()),
                        formatDouble(row.maxFrameMs()),
                        formatDouble(row.avgFps()),
                        Long.toString(row.framesAbove16Ms()),
                        Long.toString(row.framesAbove33Ms()),
                        row.jvm(),
                        row.os(),
                        Integer.toString(row.availableProcessors()))
                                + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
    }

    /**
     * Writes the benchmark rows to the requested CSV file.
     *
     * @param outputFile destination file
     * @param rows raw measured rows
     * @throws IOException if writing fails
     */
    static void write(Path outputFile, List<GuiResponsivenessBenchmarkRunner.BenchmarkRow> rows) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile");
        Objects.requireNonNull(rows, "rows");
        initialize(outputFile);
        for (var row : rows) {
            append(outputFile, row);
        }
    }

    private static void ensureInitialized(Path outputFile) throws IOException {
        if (Files.notExists(outputFile) || Files.size(outputFile) == 0L) {
            initialize(outputFile);
        }
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
