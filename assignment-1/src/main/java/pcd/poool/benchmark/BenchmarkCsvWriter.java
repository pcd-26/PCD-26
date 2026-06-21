package pcd.poool.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Writes benchmark raw runs and summaries to CSV files.
 */
public final class BenchmarkCsvWriter {

    public static final String RUNS_FILE_NAME = "benchmark-runs.csv";
    public static final String SUMMARY_FILE_NAME = "benchmark-summary.csv";

    private static final String RUNS_HEADER =
            "timestamp,implementation,balls,threads,steps,seed,runIndex,elapsedMillis,throughputStepsPerSec,checksum,status,syncTimeMillis,aggregationTimeMillis,taskSubmissionTimeMillis,joinOrFutureWaitMillis,lockAcquisitions,submittedTasks";
    private static final String SUMMARY_HEADER =
            "implementation,balls,threads,steps,runs,meanMillis,minMillis,maxMillis,stdDevMillis,meanThroughput,speedup,efficiency";
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private BenchmarkCsvWriter() {
    }

    /**
     * Exports a benchmark session to the configured output directory.
     *
     * <p>Raw runs are appended to {@value #RUNS_FILE_NAME} and the summary is
     * appended to {@value #SUMMARY_FILE_NAME}. Headers are written once.
     *
     * @param config benchmark configuration
     * @param runResults raw run results to export
     * @param summary aggregate summary for the session
     * @param sequentialBaseline sequential baseline summary used to compute speedup
     * @return paths of the generated CSV files
     * @throws IOException if writing fails
     */
    public static CsvExport export(
            BenchmarkConfig config,
            List<BenchmarkRunResult> runResults,
            BenchmarkSummary summary,
            BenchmarkSummary sequentialBaseline) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(runResults, "runResults");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(sequentialBaseline, "sequentialBaseline");
        if (summary.config() == null) {
            throw new IllegalArgumentException("summary.config must not be null");
        }

        Path outputDir = Files.createDirectories(config.outputDir());
        Path runsFile = outputDir.resolve(RUNS_FILE_NAME);
        Path summaryFile = outputDir.resolve(SUMMARY_FILE_NAME);
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());

        appendRuns(runsFile, timestamp, config, runResults);
        appendSummary(summaryFile, summary, sequentialBaseline);
        return new CsvExport(runsFile, summaryFile);
    }

    /**
     * Exports a sequential baseline session to the configured output directory.
     *
     * @param config benchmark configuration
     * @param runResults raw run results to export
     * @param summary aggregate summary for the session
     * @return paths of the generated CSV files
     * @throws IOException if writing fails
     */
    public static CsvExport export(
            BenchmarkConfig config,
            List<BenchmarkRunResult> runResults,
            BenchmarkSummary summary) throws IOException {
        if (summary.config().implementation() != BenchmarkConfig.ImplementationType.SEQUENTIAL) {
            throw new IllegalArgumentException("sequential baseline required for non-sequential summary export");
        }
        return export(config, runResults, summary, summary);
    }

    private static void appendRuns(
            Path runsFile,
            String timestamp,
            BenchmarkConfig config,
            List<BenchmarkRunResult> runResults) throws IOException {
        var lines = new StringBuilder();
        if (Files.notExists(runsFile)) {
            lines.append(RUNS_HEADER).append(System.lineSeparator());
        }
        for (var result : runResults) {
            lines.append(csvRow(
                            timestamp,
                            config.implementation().name().toLowerCase(Locale.ROOT),
                            Integer.toString(config.balls()),
                            Integer.toString(config.threads()),
                            Integer.toString(config.steps()),
                            Long.toString(config.seed()),
                            Integer.toString(result.runIndex()),
                            formatDouble(result.elapsedMillis()),
                            formatDouble(result.throughputStepsPerSecond()),
                            Long.toString(result.checksum()),
                            result.status().name(),
                            formatDouble(result.instrumentation().syncTimeMillis()),
                            formatDouble(result.instrumentation().aggregationTimeMillis()),
                            formatDouble(result.instrumentation().taskSubmissionTimeMillis()),
                            formatDouble(result.instrumentation().joinOrFutureWaitMillis()),
                            Long.toString(result.instrumentation().lockAcquisitions()),
                            Long.toString(result.instrumentation().submittedTasks())))
                    .append(System.lineSeparator());
        }
        write(runsFile, lines);
    }

    private static void appendSummary(
            Path summaryFile,
            BenchmarkSummary summary,
            BenchmarkSummary sequentialBaseline) throws IOException {
        double speedup = summary.speedupAgainst(sequentialBaseline);
        double efficiency = summary.efficiencyAgainst(sequentialBaseline);
        var line = new StringBuilder();
        if (Files.notExists(summaryFile)) {
            line.append(SUMMARY_HEADER).append(System.lineSeparator());
        }
        line.append(csvRow(
                        summary.config().implementation().name().toLowerCase(Locale.ROOT),
                        Integer.toString(summary.config().balls()),
                        Integer.toString(summary.config().threads()),
                        Integer.toString(summary.config().steps()),
                        Integer.toString(summary.measuredRuns()),
                        formatDouble(summary.meanElapsedMillis()),
                        formatDouble(summary.minElapsedMillis()),
                        formatDouble(summary.maxElapsedMillis()),
                        formatDouble(summary.stddevElapsedMillis()),
                        formatDouble(summary.meanThroughputStepsPerSecond()),
                        formatDouble(speedup),
                        formatDouble(efficiency)))
                .append(System.lineSeparator());
        write(summaryFile, line);
    }

    private static void write(Path file, StringBuilder content) throws IOException {
        if (Files.exists(file)) {
            Files.writeString(file, content.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } else {
            Files.writeString(file, content.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
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

    /**
     * Paths of the exported benchmark CSV files.
     *
     * @param runsFile raw-run CSV path
     * @param summaryFile summary CSV path
     */
    public record CsvExport(Path runsFile, Path summaryFile) {
    }
}
