package pcd.poool.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads benchmark summaries and exports report-ready scalability tables.
 *
 * <p>The analyzer consumes the aggregated benchmark summaries and derives
 * speedup, efficiency, and per-ball scalability tables that can be pasted into
 * the assignment report.
 */
public final class BenchmarkScalabilityAnalyzer {

    public static final String SPEEDUP_TABLE_FILE_NAME = "speedup-table.csv";
    public static final String EFFICIENCY_TABLE_FILE_NAME = "efficiency-table.csv";
    public static final String SCALABILITY_TABLE_FILE_NAME = "scalability-table.csv";

    private static final double SATURATION_RATIO = 0.95;
    private static final double EPSILON = 1e-9;

    private static final String SPEEDUP_HEADER =
            "balls,steps,implementation,threads,meanMillis,medianMillis,meanThroughput,medianThroughput,meanCpuUtilizationPercent,medianCpuUtilizationPercent,sequentialMedianMillis,speedup,speedupBelowOne";
    private static final String EFFICIENCY_HEADER =
            "balls,steps,implementation,threads,meanMillis,medianMillis,meanThroughput,medianThroughput,meanCpuUtilizationPercent,medianCpuUtilizationPercent,sequentialMedianMillis,speedup,efficiency,efficiencyDegradation";
    private static final String SCALABILITY_HEADER =
            "balls,steps,sequentialThroughput,threadedBestThreads,threadedBestThroughput,threadedCpuUtilization,threadedSpeedup,threadedEfficiency,threadedSaturationPoint,threadedSlowerThanSequential,threadedEfficiencyDegradation,executorBestThreads,executorBestThroughput,executorCpuUtilization,executorSpeedup,executorEfficiency,executorSaturationPoint,executorSlowerThanThreaded,executorEfficiencyDegradation";

    private BenchmarkScalabilityAnalyzer() {
    }

    /**
     * Runs the analyzer from the command line.
     *
     * <p>Arguments:
     * <ol>
     *   <li>summary directory or `benchmark-summary.csv` file</li>
     *   <li>optional output directory, defaults to the input directory</li>
     * </ol>
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        try {
            Path input = args.length > 0 ? Path.of(args[0]) : Path.of("benchmarks", "results");
            Path output = args.length > 1 ? Path.of(args[1]) : input;
            var report = analyze(input, output);
            System.out.printf(Locale.US,
                    "analysis_completed summary_rows=%d speedup_rows=%d efficiency_rows=%d scalability_rows=%d output_dir=%s%n",
                    report.summaryRows(),
                    report.speedupRows(),
                    report.efficiencyRows(),
                    report.scalabilityRows(),
                    report.outputDir());
        } catch (Exception ex) {
            System.err.printf(Locale.US, "analysis_failed message=%s%n", ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Reads benchmark summaries and writes derived scalability tables.
     *
     * @param input summary directory or file
     * @param outputDir directory for derived tables
     * @return analysis report with generated row counts
     * @throws IOException if reading or writing fails
     */
    public static AnalysisReport analyze(Path input, Path outputDir) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(outputDir, "outputDir");

        Path summaryFile = Files.isDirectory(input)
                ? input.resolve(BenchmarkCsvWriter.SUMMARY_FILE_NAME)
                : input;
        List<SummaryRow> rows = readSummaryRows(summaryFile);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("benchmark-summary.csv must contain at least one row");
        }

        Path resolvedOutputDir = outputDir;
        Files.createDirectories(resolvedOutputDir);

        List<SpeedupRow> speedupRows = buildSpeedupRows(rows);
        List<EfficiencyRow> efficiencyRows = buildEfficiencyRows(rows, speedupRows);
        List<ScalabilityRow> scalabilityRows = buildScalabilityRows(rows, speedupRows, efficiencyRows);

        writeCsv(resolvedOutputDir.resolve(SPEEDUP_TABLE_FILE_NAME), SPEEDUP_HEADER, speedupRows);
        writeCsv(resolvedOutputDir.resolve(EFFICIENCY_TABLE_FILE_NAME), EFFICIENCY_HEADER, efficiencyRows);
        writeCsv(resolvedOutputDir.resolve(SCALABILITY_TABLE_FILE_NAME), SCALABILITY_HEADER, scalabilityRows);

        return new AnalysisReport(resolvedOutputDir, rows.size(), speedupRows.size(), efficiencyRows.size(), scalabilityRows.size());
    }

    private static List<SpeedupRow> buildSpeedupRows(List<SummaryRow> rows) {
        Map<ScenarioKey, SummaryRow> baselines = baselineByScenario(rows);
        var output = new ArrayList<SpeedupRow>(rows.size());
        for (var row : rows) {
            SummaryRow baseline = baselines.get(new ScenarioKey(row.balls(), row.steps()));
            double speedup = baseline == null || row.medianMillis() <= 0.0 ? Double.NaN : baseline.medianMillis() / row.medianMillis();
            output.add(new SpeedupRow(
                    row.balls(),
                    row.steps(),
                    row.implementation(),
                    row.threads(),
                    row.meanMillis(),
                    row.medianMillis(),
                    row.meanThroughput(),
                    row.medianThroughput(),
                    row.meanCpuUtilization(),
                    row.medianCpuUtilization(),
                    baseline == null ? Double.NaN : baseline.medianMillis(),
                    speedup,
                    !Double.isNaN(speedup) && speedup < 1.0));
        }
        return List.copyOf(output);
    }

    private static List<EfficiencyRow> buildEfficiencyRows(List<SummaryRow> rows, List<SpeedupRow> speedupRows) {
        var output = new ArrayList<EfficiencyRow>(rows.size());
        Map<ScenarioKey, List<SpeedupRow>> grouped = groupSpeedupRows(speedupRows);
        for (var entry : grouped.entrySet()) {
            var scenarioRows = entry.getValue();
            double previousEfficiency = Double.NaN;
            for (var row : scenarioRows) {
                double efficiency = row.speedup() / Math.max(1, row.threads());
                boolean degradation = !Double.isNaN(previousEfficiency) && efficiency + EPSILON < previousEfficiency;
                output.add(new EfficiencyRow(
                        row.balls(),
                        row.steps(),
                        row.implementation(),
                        row.threads(),
                        row.meanMillis(),
                        row.medianMillis(),
                        row.meanThroughput(),
                        row.medianThroughput(),
                        row.meanCpuUtilization(),
                        row.medianCpuUtilization(),
                        row.sequentialMedianMillis(),
                        row.speedup(),
                        efficiency,
                        degradation));
                previousEfficiency = efficiency;
            }
        }
        return List.copyOf(output);
    }

    private static List<ScalabilityRow> buildScalabilityRows(
            List<SummaryRow> rows,
            List<SpeedupRow> speedupRows,
            List<EfficiencyRow> efficiencyRows) {
        Map<ScenarioKey, List<SummaryRow>> byScenario = groupByScenario(rows);
        Map<RowKey, SpeedupRow> speedupByKey = indexSpeedupRows(speedupRows);
        Map<RowKey, EfficiencyRow> efficiencyByKey = indexEfficiencyRows(efficiencyRows);
        var output = new ArrayList<ScalabilityRow>(byScenario.size());

        for (var entry : byScenario.entrySet()) {
            ScenarioKey scenario = entry.getKey();
            List<SummaryRow> scenarioRows = entry.getValue();
            SummaryRow sequential = findImplementation(scenarioRows, BenchmarkConfig.ImplementationType.SEQUENTIAL);
            List<SummaryRow> threadedRows = rowsForImplementation(scenarioRows, BenchmarkConfig.ImplementationType.THREADS);
            List<SummaryRow> executorRows = rowsForImplementation(scenarioRows, BenchmarkConfig.ImplementationType.EXECUTOR);

            SummaryRow bestThreaded = bestByThroughput(threadedRows);
            SummaryRow bestExecutor = bestByThroughput(executorRows);

            boolean threadedEfficiencyDegradation = hasEfficiencyDegradation(scenario.balls(), scenario.steps(), BenchmarkConfig.ImplementationType.THREADS, efficiencyByKey);
            boolean executorEfficiencyDegradation = hasEfficiencyDegradation(scenario.balls(), scenario.steps(), BenchmarkConfig.ImplementationType.EXECUTOR, efficiencyByKey);

            output.add(new ScalabilityRow(
                    scenario.balls(),
                    scenario.steps(),
                    sequential == null ? Double.NaN : sequential.medianThroughput(),
                    bestThreaded == null ? 0 : bestThreaded.threads(),
                    bestThreaded == null ? Double.NaN : bestThreaded.medianThroughput(),
                    bestThreaded == null ? Double.NaN : bestThreaded.medianCpuUtilization(),
                    bestThreaded == null || sequential == null || bestThreaded.medianMillis() <= 0.0 ? Double.NaN : sequential.medianMillis() / bestThreaded.medianMillis(),
                    bestThreaded == null ? Double.NaN : (sequential == null || bestThreaded.medianMillis() <= 0.0 ? Double.NaN : (sequential.medianMillis() / bestThreaded.medianMillis()) / Math.max(1, bestThreaded.threads())),
                    saturationPoint(threadedRows),
                    bestThreaded != null && sequential != null && bestThreaded.medianThroughput() < sequential.medianThroughput(),
                    threadedEfficiencyDegradation,
                    bestExecutor == null ? 0 : bestExecutor.threads(),
                    bestExecutor == null ? Double.NaN : bestExecutor.medianThroughput(),
                    bestExecutor == null ? Double.NaN : bestExecutor.medianCpuUtilization(),
                    bestExecutor == null || sequential == null || bestExecutor.medianMillis() <= 0.0 ? Double.NaN : sequential.medianMillis() / bestExecutor.medianMillis(),
                    bestExecutor == null ? Double.NaN : (sequential == null || bestExecutor.medianMillis() <= 0.0 ? Double.NaN : (sequential.medianMillis() / bestExecutor.medianMillis()) / Math.max(1, bestExecutor.threads())),
                    saturationPoint(executorRows),
                    bestExecutor != null && bestThreaded != null && bestExecutor.medianThroughput() < bestThreaded.medianThroughput(),
                    executorEfficiencyDegradation));
        }
        return List.copyOf(output);
    }

    private static Map<ScenarioKey, SummaryRow> baselineByScenario(List<SummaryRow> rows) {
        Map<ScenarioKey, SummaryRow> baselines = new LinkedHashMap<>();
        for (var row : rows) {
            if (row.implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL) {
                baselines.put(new ScenarioKey(row.balls(), row.steps()), row);
            }
        }
        return baselines;
    }

    private static Map<ScenarioKey, List<SummaryRow>> groupByScenario(List<SummaryRow> rows) {
        Map<ScenarioKey, List<SummaryRow>> grouped = new LinkedHashMap<>();
        for (var row : rows) {
            grouped.computeIfAbsent(new ScenarioKey(row.balls(), row.steps()), key -> new ArrayList<>()).add(row);
        }
        for (var entry : grouped.entrySet()) {
            entry.getValue().sort(Comparator
                    .comparingInt(SummaryRow::balls)
                    .thenComparingInt(SummaryRow::steps)
                    .thenComparing(row -> row.implementation().ordinal())
                    .thenComparingInt(SummaryRow::threads));
        }
        return grouped;
    }

    private static Map<ScenarioKey, List<SpeedupRow>> groupSpeedupRows(List<SpeedupRow> rows) {
        Map<ScenarioKey, List<SpeedupRow>> grouped = new LinkedHashMap<>();
        for (var row : rows) {
            grouped.computeIfAbsent(new ScenarioKey(row.balls(), row.steps()), key -> new ArrayList<>()).add(row);
        }
        for (var entry : grouped.entrySet()) {
            entry.getValue().sort(Comparator
                    .comparingInt(SpeedupRow::balls)
                    .thenComparingInt(SpeedupRow::steps)
                    .thenComparing(row -> row.implementation().ordinal())
                    .thenComparingInt(SpeedupRow::threads));
        }
        return grouped;
    }

    private static Map<RowKey, SpeedupRow> indexSpeedupRows(List<SpeedupRow> rows) {
        Map<RowKey, SpeedupRow> indexed = new LinkedHashMap<>();
        for (var row : rows) {
            indexed.put(new RowKey(row.balls(), row.steps(), row.implementation(), row.threads()), row);
        }
        return indexed;
    }

    private static Map<RowKey, EfficiencyRow> indexEfficiencyRows(List<EfficiencyRow> rows) {
        Map<RowKey, EfficiencyRow> indexed = new LinkedHashMap<>();
        for (var row : rows) {
            indexed.put(new RowKey(row.balls(), row.steps(), row.implementation(), row.threads()), row);
        }
        return indexed;
    }

    private static boolean hasEfficiencyDegradation(
            int balls,
            int steps,
            BenchmarkConfig.ImplementationType implementation,
            Map<RowKey, EfficiencyRow> efficiencyRows) {
        List<EfficiencyRow> scenarioRows = new ArrayList<>();
        for (var entry : efficiencyRows.entrySet()) {
            RowKey key = entry.getKey();
            if (key.balls() == balls && key.steps() == steps && key.implementation() == implementation) {
                scenarioRows.add(entry.getValue());
            }
        }
        scenarioRows.sort(Comparator.comparingInt(EfficiencyRow::threads));
        double previous = Double.NaN;
        for (var row : scenarioRows) {
            if (!Double.isNaN(previous) && row.efficiency() + EPSILON < previous) {
                return true;
            }
            previous = row.efficiency();
        }
        return false;
    }

    private static List<SummaryRow> rowsForImplementation(List<SummaryRow> rows, BenchmarkConfig.ImplementationType implementation) {
        var output = new ArrayList<SummaryRow>();
        for (var row : rows) {
            if (row.implementation() == implementation) {
                output.add(row);
            }
        }
        output.sort(Comparator.comparingInt(SummaryRow::threads));
        return List.copyOf(output);
    }

    private static SummaryRow findImplementation(List<SummaryRow> rows, BenchmarkConfig.ImplementationType implementation) {
        for (var row : rows) {
            if (row.implementation() == implementation) {
                return row;
            }
        }
        return null;
    }

    private static SummaryRow bestByThroughput(List<SummaryRow> rows) {
        SummaryRow best = null;
        for (var row : rows) {
            if (best == null || row.medianThroughput() > best.medianThroughput()
                    || (Math.abs(row.medianThroughput() - best.medianThroughput()) <= EPSILON && row.threads() < best.threads())) {
                best = row;
            }
        }
        return best;
    }

    private static int saturationPoint(List<SummaryRow> rows) {
        SummaryRow best = bestByThroughput(rows);
        if (best == null || best.medianThroughput() <= 0.0) {
            return 0;
        }
        double threshold = best.medianThroughput() * SATURATION_RATIO;
        for (var row : rows) {
            if (row.medianThroughput() >= threshold) {
                return row.threads();
            }
        }
        return best.threads();
    }

    private static List<SummaryRow> readSummaryRows(Path summaryFile) throws IOException {
        if (!Files.exists(summaryFile)) {
            throw new IllegalArgumentException("missing benchmark summary file: " + summaryFile);
        }
        List<String> lines = Files.readAllLines(summaryFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return List.of();
        }

        List<String> header = parseCsvLine(lines.get(0));
        Map<String, Integer> columns = indexColumns(header);
        var rows = new ArrayList<SummaryRow>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = parseCsvLine(line);
            rows.add(new SummaryRow(
                    parseImplementation(cells, columns, "implementation"),
                    parseInt(cells, columns, "balls"),
                    parseInt(cells, columns, "threads"),
                    parseInt(cells, columns, "steps"),
                    parseInt(cells, columns, "runs"),
                    parseDouble(cells, columns, "meanMillis"),
                    parseDouble(cells, columns, "medianMillis"),
                    parseDouble(cells, columns, "minMillis"),
                    parseDouble(cells, columns, "maxMillis"),
                    parseDouble(cells, columns, "stdDevMillis"),
                    parseDouble(cells, columns, "meanThroughput"),
                    parseDouble(cells, columns, "medianThroughput"),
                    parseDouble(cells, columns, "meanCpuUtilizationPercent"),
                    parseDouble(cells, columns, "medianCpuUtilizationPercent"),
                    parseDouble(cells, columns, "speedup"),
                    parseDouble(cells, columns, "efficiency")));
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
        return BenchmarkConfig.ImplementationType.parse(value(cells, columns, column));
    }

    private static int parseInt(List<String> cells, Map<String, Integer> columns, String column) {
        return Integer.parseInt(value(cells, columns, column));
    }

    private static double parseDouble(List<String> cells, Map<String, Integer> columns, String column) {
        String value = value(cells, columns, column);
        if (value.isBlank()) {
            return Double.NaN;
        }
        return Double.parseDouble(value);
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

    private static String formatDouble(double value) {
        if (Double.isNaN(value)) {
            return "";
        }
        return String.format(Locale.US, "%.6f", value);
    }

    private static String formatBoolean(boolean value) {
        return Boolean.toString(value);
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

    private record ScenarioKey(int balls, int steps) {
    }

    private record RowKey(int balls, int steps, BenchmarkConfig.ImplementationType implementation, int threads) {
    }

    private record SummaryRow(
            BenchmarkConfig.ImplementationType implementation,
            int balls,
            int threads,
            int steps,
            int runs,
            double meanMillis,
            double medianMillis,
            double minMillis,
            double maxMillis,
            double stdDevMillis,
            double meanThroughput,
            double medianThroughput,
            double meanCpuUtilization,
            double medianCpuUtilization,
            double speedup,
            double efficiency) {
    }

    private record SpeedupRow(
            int balls,
            int steps,
            BenchmarkConfig.ImplementationType implementation,
            int threads,
            double meanMillis,
            double medianMillis,
            double meanThroughput,
            double medianThroughput,
            double meanCpuUtilization,
            double medianCpuUtilization,
            double sequentialMedianMillis,
            double speedup,
            boolean speedupBelowOne) implements CsvRow {

        @Override
        public String toCsv() {
            return csvRow(
                    Integer.toString(balls),
                    Integer.toString(steps),
                    implementation.name().toLowerCase(Locale.ROOT),
                    Integer.toString(threads),
                    formatDouble(meanMillis),
                    formatDouble(medianMillis),
                    formatDouble(meanThroughput),
                    formatDouble(medianThroughput),
                    formatDouble(meanCpuUtilization),
                    formatDouble(medianCpuUtilization),
                    formatDouble(sequentialMedianMillis),
                    formatDouble(speedup),
                    formatBoolean(speedupBelowOne));
        }
    }

    private record EfficiencyRow(
            int balls,
            int steps,
            BenchmarkConfig.ImplementationType implementation,
            int threads,
            double meanMillis,
            double medianMillis,
            double meanThroughput,
            double medianThroughput,
            double meanCpuUtilization,
            double medianCpuUtilization,
            double sequentialMedianMillis,
            double speedup,
            double efficiency,
            boolean efficiencyDegradation) implements CsvRow {

        @Override
        public String toCsv() {
            return csvRow(
                    Integer.toString(balls),
                    Integer.toString(steps),
                    implementation.name().toLowerCase(Locale.ROOT),
                    Integer.toString(threads),
                    formatDouble(meanMillis),
                    formatDouble(medianMillis),
                    formatDouble(meanThroughput),
                    formatDouble(medianThroughput),
                    formatDouble(meanCpuUtilization),
                    formatDouble(medianCpuUtilization),
                    formatDouble(sequentialMedianMillis),
                    formatDouble(speedup),
                    formatDouble(efficiency),
                    formatBoolean(efficiencyDegradation));
        }
    }

    private record ScalabilityRow(
            int balls,
            int steps,
            double sequentialThroughput,
            int threadedBestThreads,
            double threadedBestThroughput,
            double threadedCpuUtilization,
            double threadedSpeedup,
            double threadedEfficiency,
            int threadedSaturationPoint,
            boolean threadedSlowerThanSequential,
            boolean threadedEfficiencyDegradation,
            int executorBestThreads,
            double executorBestThroughput,
            double executorCpuUtilization,
            double executorSpeedup,
            double executorEfficiency,
            int executorSaturationPoint,
            boolean executorSlowerThanThreaded,
            boolean executorEfficiencyDegradation) implements CsvRow {

        @Override
        public String toCsv() {
            return csvRow(
                    Integer.toString(balls),
                    Integer.toString(steps),
                    formatDouble(sequentialThroughput),
                    Integer.toString(threadedBestThreads),
                    formatDouble(threadedBestThroughput),
                    formatDouble(threadedCpuUtilization),
                    formatDouble(threadedSpeedup),
                    formatDouble(threadedEfficiency),
                    Integer.toString(threadedSaturationPoint),
                    formatBoolean(threadedSlowerThanSequential),
                    formatBoolean(threadedEfficiencyDegradation),
                    Integer.toString(executorBestThreads),
                    formatDouble(executorBestThroughput),
                    formatDouble(executorCpuUtilization),
                    formatDouble(executorSpeedup),
                    formatDouble(executorEfficiency),
                    Integer.toString(executorSaturationPoint),
                    formatBoolean(executorSlowerThanThreaded),
                    formatBoolean(executorEfficiencyDegradation));
        }
    }

    /**
     * Result summary for an analysis pass.
     *
     * @param outputDir directory where the derived CSV files were written
     * @param summaryRows number of input summary rows
     * @param speedupRows number of output speedup rows
     * @param efficiencyRows number of output efficiency rows
     * @param scalabilityRows number of output scalability rows
     */
    public record AnalysisReport(
            Path outputDir,
            int summaryRows,
            int speedupRows,
            int efficiencyRows,
            int scalabilityRows) {
    }
}
