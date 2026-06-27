package pcd.poool.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Writes the raw CSV output for the headless benchmark runner.
 */
final class HeadlessBenchmarkCsvWriter {

    private static final String HEADER =
            "implementation,balls,workers,steps,seed,runIndex,warmup,elapsedMs,throughput,stateHash,jvm,os,availableProcessors";

    private HeadlessBenchmarkCsvWriter() {
    }

    /**
     * Writes the benchmark rows to the requested CSV file.
     *
     * @param outputFile destination file
     * @param rows raw measured rows
     * @throws IOException if writing fails
     */
    static void write(Path outputFile, List<HeadlessBenchmarkRunner.BenchmarkRow> rows) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile");
        Objects.requireNonNull(rows, "rows");

        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        var content = new StringBuilder();
        content.append(HEADER).append(System.lineSeparator());
        for (var row : rows) {
            content.append(csvRow(
                            row.implementation(),
                            Integer.toString(row.balls()),
                            Integer.toString(row.workers()),
                            Integer.toString(row.steps()),
                            Long.toString(row.seed()),
                            Integer.toString(row.runIndex()),
                            Boolean.toString(row.warmup()),
                            formatDouble(row.elapsedMs()),
                            formatDouble(row.throughput()),
                            Long.toString(row.stateHash()),
                            row.jvm(),
                            row.os(),
                            Integer.toString(row.availableProcessors())))
                    .append(System.lineSeparator());
        }

        Files.writeString(
                outputFile,
                content.toString(),
                StandardCharsets.UTF_8);
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
