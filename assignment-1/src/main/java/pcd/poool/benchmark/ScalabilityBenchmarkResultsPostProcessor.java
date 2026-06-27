package pcd.poool.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Post-processes the scalability benchmark raw CSV into an aggregated CSV.
 */
final class ScalabilityBenchmarkResultsPostProcessor {

    static final String AGGREGATED_FILE_NAME = "aggregated-scalability-results.csv";

    private static final String AGGREGATED_HEADER =
            "implementation,balls,workers,steps,seed,avgElapsedMs,stdElapsedMs,avgThroughput,stdThroughput,avgCoordinationMs,stdCoordinationMs,avgCoordinationRatio,stdCoordinationRatio,avgTasksSubmitted";

    private ScalabilityBenchmarkResultsPostProcessor() {
    }

    /**
     * Reads the raw scalability CSV and writes the aggregated CSV.
     *
     * @param rawResultsFile raw scalability CSV
     * @return generated derived scalability data
     * @throws IOException if reading or writing fails
     */
    static DerivedResults process(Path rawResultsFile) throws IOException {
        Objects.requireNonNull(rawResultsFile, "rawResultsFile");
        Path parent = rawResultsFile.getParent();
        Path outputDir = parent == null ? Path.of(".") : parent;
        Files.createDirectories(outputDir);

        List<RawRunRow> rawRows = readRawRows(rawResultsFile);
        List<AggregateRow> aggregatedRows = buildAggregatedRows(rawRows);

        Path aggregatedFile = outputDir.resolve(AGGREGATED_FILE_NAME);
        writeCsv(aggregatedFile, AGGREGATED_HEADER, aggregatedRows);

        return new DerivedResults(aggregatedFile, List.copyOf(aggregatedRows));
    }

    private static List<AggregateRow> buildAggregatedRows(List<RawRunRow> rawRows) {
        var grouped = new LinkedHashMap<AggregateKey, List<RawRunRow>>();
        for (var row : rawRows) {
            if (row.warmup()) {
                continue;
            }
            grouped.computeIfAbsent(row.key(), ignored -> new ArrayList<>()).add(row);
        }

        var aggregatedRows = new ArrayList<AggregateRow>(grouped.size());
        for (var entry : grouped.entrySet()) {
            AggregateKey key = entry.getKey();
            List<RawRunRow> rows = entry.getValue();
            aggregatedRows.add(new AggregateRow(
                    key.implementation(),
                    key.balls(),
                    key.workers(),
                    key.steps(),
                    key.seed(),
                    mean(rows, RawRunRow::elapsedMs),
                    stddev(rows, RawRunRow::elapsedMs),
                    mean(rows, RawRunRow::throughput),
                    stddev(rows, RawRunRow::throughput),
                    mean(rows, RawRunRow::coordinationMs),
                    stddev(rows, RawRunRow::coordinationMs),
                    mean(rows, RawRunRow::coordinationRatio),
                    stddev(rows, RawRunRow::coordinationRatio),
                    mean(rows, row -> row.tasksSubmitted())));
        }

        aggregatedRows.sort(Comparator
                .comparing((AggregateRow row) -> row.implementation().ordinal())
                .thenComparingInt(AggregateRow::balls)
                .thenComparingInt(AggregateRow::workers)
                .thenComparingInt(AggregateRow::steps)
                .thenComparingLong(AggregateRow::seed));
        return List.copyOf(aggregatedRows);
    }

    private static List<RawRunRow> readRawRows(Path rawResultsFile) throws IOException {
        if (!Files.exists(rawResultsFile)) {
            throw new IllegalArgumentException("missing benchmark raw results file: " + rawResultsFile);
        }
        List<String> lines = Files.readAllLines(rawResultsFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> columns = indexColumns(parseCsvLine(lines.get(0)));
        var rows = new ArrayList<RawRunRow>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            rows.add(new RawRunRow(
                    parseImplementation(cells, columns, "implementation"),
                    parseInt(cells, columns, "balls"),
                    parseInt(cells, columns, "workers"),
                    parseInt(cells, columns, "steps"),
                    parseLong(cells, columns, "seed"),
                    parseInt(cells, columns, "runIndex"),
                    parseBoolean(cells, columns, "warmup"),
                    parseDouble(cells, columns, "elapsedMs"),
                    parseDouble(cells, columns, "throughput"),
                    parseDouble(cells, columns, "coordinationMs"),
                    parseDouble(cells, columns, "coordinationRatio"),
                    parseLong(cells, columns, "tasksSubmitted"),
                    value(cells, columns, "jvm"),
                    value(cells, columns, "os"),
                    parseInt(cells, columns, "availableProcessors")));
        }
        return List.copyOf(rows);
    }

    private static Map<String, Integer> indexColumns(List<String> header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i), i);
        }
        return columns;
    }

    private static BenchmarkConfig.ImplementationType parseImplementation(List<String> cells, Map<String, Integer> columns, String column) {
        return BenchmarkConfig.ImplementationType.valueOf(value(cells, columns, column).toUpperCase(Locale.ROOT));
    }

    private static int parseInt(List<String> cells, Map<String, Integer> columns, String column) {
        return Integer.parseInt(value(cells, columns, column));
    }

    private static long parseLong(List<String> cells, Map<String, Integer> columns, String column) {
        return Long.parseLong(value(cells, columns, column));
    }

    private static boolean parseBoolean(List<String> cells, Map<String, Integer> columns, String column) {
        return Boolean.parseBoolean(value(cells, columns, column));
    }

    private static double parseDouble(List<String> cells, Map<String, Integer> columns, String column) {
        return Double.parseDouble(value(cells, columns, column));
    }

    private static String value(List<String> cells, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index < 0 || index >= cells.size()) {
            throw new IllegalArgumentException("missing CSV column: " + column);
        }
        return cells.get(index);
    }

    private static List<String> parseCsvLine(String line) {
        var cells = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == ',') {
                cells.add(current.toString());
                current.setLength(0);
            } else if (ch == '"') {
                quoted = true;
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return List.copyOf(cells);
    }

    private static void writeCsv(Path file, String header, List<? extends CsvRow> rows) throws IOException {
        var content = new StringBuilder();
        content.append(header).append(System.lineSeparator());
        for (var row : rows) {
            content.append(row.toCsv()).append(System.lineSeparator());
        }
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static double mean(List<RawRunRow> rows, ToDoubleFunction<RawRunRow> extractor) {
        double sum = 0.0;
        for (var row : rows) {
            sum += extractor.applyAsDouble(row);
        }
        return sum / rows.size();
    }

    private static double stddev(List<RawRunRow> rows, ToDoubleFunction<RawRunRow> extractor) {
        double avg = mean(rows, extractor);
        double sum = 0.0;
        for (var row : rows) {
            double delta = extractor.applyAsDouble(row) - avg;
            sum += delta * delta;
        }
        return Math.sqrt(sum / rows.size());
    }

    private static String formatDouble(double value) {
        if (Double.isNaN(value)) {
            return "";
        }
        return String.format(Locale.US, "%.6f", value);
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

    private interface CsvRow {
        String toCsv();
    }

    private interface ToDoubleFunction<T> {
        double applyAsDouble(T value);
    }

    private record AggregateKey(
            BenchmarkConfig.ImplementationType implementation,
            int balls,
            int workers,
            int steps,
            long seed) {
    }

    private record RawRunRow(
            BenchmarkConfig.ImplementationType implementation,
            int balls,
            int workers,
            int steps,
            long seed,
            int runIndex,
            boolean warmup,
            double elapsedMs,
            double throughput,
            double coordinationMs,
            double coordinationRatio,
            long tasksSubmitted,
            String jvm,
            String os,
            int availableProcessors) {

        private AggregateKey key() {
            return new AggregateKey(implementation, balls, workers, steps, seed);
        }
    }

    private record AggregateRow(
            BenchmarkConfig.ImplementationType implementation,
            int balls,
            int workers,
            int steps,
            long seed,
            double avgElapsedMs,
            double stdElapsedMs,
            double avgThroughput,
            double stdThroughput,
            double avgCoordinationMs,
            double stdCoordinationMs,
            double avgCoordinationRatio,
            double stdCoordinationRatio,
            double avgTasksSubmitted) implements CsvRow {

        @Override
        public String toCsv() {
            return csvRow(
                    implementation.name().toLowerCase(Locale.ROOT),
                    Integer.toString(balls),
                    Integer.toString(workers),
                    Integer.toString(steps),
                    Long.toString(seed),
                    formatDouble(avgElapsedMs),
                    formatDouble(stdElapsedMs),
                    formatDouble(avgThroughput),
                    formatDouble(stdThroughput),
                    formatDouble(avgCoordinationMs),
                    formatDouble(stdCoordinationMs),
                    formatDouble(avgCoordinationRatio),
                    formatDouble(stdCoordinationRatio),
                    formatDouble(avgTasksSubmitted));
        }
    }

    /**
     * Generated aggregated scalability data.
     *
     * @param aggregatedFile aggregated CSV file path
     * @param aggregatedRows grouped benchmark rows
     */
    public record DerivedResults(Path aggregatedFile, List<AggregateRow> aggregatedRows) {
    }
}
