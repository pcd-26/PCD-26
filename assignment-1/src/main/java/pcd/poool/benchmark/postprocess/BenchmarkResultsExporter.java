package pcd.poool.benchmark.postprocess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import pcd.poool.benchmark.config.BenchmarkConfig;
import pcd.poool.benchmark.core.BenchmarkSummary;
import pcd.poool.benchmark.core.BenchmarkWorkloads;
import pcd.poool.benchmark.core.RuntimeTelemetry;
import pcd.poool.model.physics.common.Boundary;

/**
 * Exports derived benchmark tables and run metadata.
 */
public final class BenchmarkResultsExporter {

    static final String METADATA_FILE_NAME = "benchmark-runtime-metadata.csv";
    static final String AVG_TICK_TIME_FILE_NAME = "avg-tick-time-by-engine.csv";
    static final String THROUGHPUT_FILE_NAME = "throughput-by-engine.csv";
    static final String SPEEDUP_FILE_NAME = "speedup-by-worker-count.csv";
    static final String CROSSOVER_FILE_NAME = "crossover-workloads.csv";

    private static final double SPEEDUP_THRESHOLD = 1.0;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

    private static final String METADATA_HEADER =
            "timestamp_utc,git_commit_hash,max_threads,os_name,os_version,os_arch,java_version,jvm_name,board_width,board_height,implementation,balls,threads,steps,seed,warmup_runs,measured_runs,benchmark_config";
    private static final String AVG_TICK_TIME_HEADER =
            "engine_name,board_width,board_height,balls,threads,steps,seed,avg_tick_time_ns,min_tick_time_ns,max_tick_time_ns,std_tick_time_ns,throughput_steps_per_sec";
    private static final String THROUGHPUT_HEADER =
            "engine_name,board_width,board_height,balls,threads,steps,seed,throughput_steps_per_sec";
    private static final String SPEEDUP_HEADER =
            "engine_name,board_width,board_height,balls,threads,steps,seed,worker_count,speedup_vs_sequential";
    private static final String CROSSOVER_HEADER =
            "engine_name,board_width,board_height,worker_count,seed,crossover_balls,steps,avg_tick_time_ns,throughput_steps_per_sec,speedup_vs_sequential";

    private static final Boundary BENCHMARK_BOARD_BOUNDARY = BenchmarkWorkloads.DEFAULT_BOARD_BOUNDARY;

    private BenchmarkResultsExporter() {
    }

    public static ExportedResults export(
            Path outputDir,
            Instant timestamp,
            RuntimeTelemetry telemetry,
            String gitCommitHash,
            List<BenchmarkSummary> summaries) throws IOException {
        Objects.requireNonNull(outputDir, "outputDir");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(summaries, "summaries");

        Files.createDirectories(outputDir);

        Map<ScenarioKey, BenchmarkSummary> baselines = baselineByScenario(summaries);
        List<DerivedRow> derivedRows = buildDerivedRows(summaries, baselines);

        List<MetadataRow> metadataRows = buildMetadataRows(timestamp, telemetry, gitCommitHash, summaries);
        List<AvgTickTimeRow> avgTickRows = buildAvgTickRows(derivedRows);
        List<ThroughputRow> throughputRows = buildThroughputRows(derivedRows);
        List<SpeedupRow> speedupRows = buildSpeedupRows(derivedRows);
        List<CrossoverRow> crossoverRows = buildCrossoverRows(derivedRows);

        Path metadataFile = outputDir.resolve(METADATA_FILE_NAME);
        Path avgTickFile = outputDir.resolve(AVG_TICK_TIME_FILE_NAME);
        Path throughputFile = outputDir.resolve(THROUGHPUT_FILE_NAME);
        Path speedupFile = outputDir.resolve(SPEEDUP_FILE_NAME);
        Path crossoverFile = outputDir.resolve(CROSSOVER_FILE_NAME);

        writeCsv(metadataFile, METADATA_HEADER, metadataRows);
        writeCsv(avgTickFile, AVG_TICK_TIME_HEADER, avgTickRows);
        writeCsv(throughputFile, THROUGHPUT_HEADER, throughputRows);
        writeCsv(speedupFile, SPEEDUP_HEADER, speedupRows);
        writeCsv(crossoverFile, CROSSOVER_HEADER, crossoverRows);

        return new ExportedResults(metadataFile, avgTickFile, throughputFile, speedupFile, crossoverFile);
    }

    public static String resolveGitCommitHash() {
        try {
            var process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            byte[] bytes = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                return "";
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private static Map<ScenarioKey, BenchmarkSummary> baselineByScenario(List<BenchmarkSummary> summaries) {
        Map<ScenarioKey, BenchmarkSummary> baselines = new LinkedHashMap<>();
        for (var summary : summaries) {
            if (summary.config().implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL) {
                baselines.put(new ScenarioKey(summary.config().balls(), summary.config().steps(), summary.config().seed()), summary);
            }
        }
        return baselines;
    }

    private static List<DerivedRow> buildDerivedRows(
            List<BenchmarkSummary> summaries,
            Map<ScenarioKey, BenchmarkSummary> baselines) {
        var rows = new ArrayList<DerivedRow>(summaries.size());
        for (var summary : summaries) {
            BenchmarkConfig config = summary.config();
            BenchmarkSummary baseline = baselines.get(new ScenarioKey(config.balls(), config.steps(), config.seed()));
            double speedup = baseline == null || summary.medianElapsedMillis() <= 0.0
                    ? Double.NaN
                    : baseline.medianElapsedMillis() / summary.medianElapsedMillis();
            rows.add(new DerivedRow(summary, speedup));
        }
        rows.sort(Comparator
                .comparing((DerivedRow row) -> row.summary().config().implementation().ordinal())
                .thenComparingInt(row -> row.summary().config().balls())
                .thenComparingInt(row -> row.summary().config().threads())
                .thenComparingInt(row -> row.summary().config().steps())
                .thenComparingLong(row -> row.summary().config().seed()));
        return List.copyOf(rows);
    }

    private static List<MetadataRow> buildMetadataRows(
            Instant timestamp,
            RuntimeTelemetry telemetry,
            String gitCommitHash,
            List<BenchmarkSummary> summaries) {
        var rows = new ArrayList<MetadataRow>(summaries.size());
        String formattedTimestamp = TIMESTAMP_FORMATTER.format(timestamp);
        String commit = gitCommitHash == null ? "" : gitCommitHash;
        for (var summary : summaries) {
            BenchmarkConfig config = summary.config();
            rows.add(new MetadataRow(
                    formattedTimestamp,
                    commit,
                    telemetry.maxThreads(),
                    telemetry.osName(),
                    telemetry.osVersion(),
                    telemetry.osArch(),
                    telemetry.jvmVersion(),
                    telemetry.jvmName(),
                    boardWidth(),
                    boardHeight(),
                    config.implementation().name().toLowerCase(Locale.ROOT),
                    config.balls(),
                    config.threads(),
                    config.steps(),
                    config.seed(),
                    config.warmupRuns(),
                    config.measuredRuns(),
                    config.toKeyValueString()));
        }
        return List.copyOf(rows);
    }

    private static List<AvgTickTimeRow> buildAvgTickRows(List<DerivedRow> rows) {
        var output = new ArrayList<AvgTickTimeRow>(rows.size());
        for (var row : rows) {
            BenchmarkConfig config = row.summary().config();
            output.add(new AvgTickTimeRow(
                    config.implementation().name().toLowerCase(Locale.ROOT),
                    boardWidth(),
                    boardHeight(),
                    config.balls(),
                    config.threads(),
                    config.steps(),
                    config.seed(),
                    row.summary().meanElapsedMillis() * 1_000_000.0,
                    row.summary().minElapsedMillis() * 1_000_000.0,
                    row.summary().maxElapsedMillis() * 1_000_000.0,
                    row.summary().stddevElapsedMillis() * 1_000_000.0,
                    row.summary().meanThroughputStepsPerSecond()));
        }
        return List.copyOf(output);
    }

    private static List<ThroughputRow> buildThroughputRows(List<DerivedRow> rows) {
        var output = new ArrayList<ThroughputRow>(rows.size());
        for (var row : rows) {
            BenchmarkConfig config = row.summary().config();
            output.add(new ThroughputRow(
                    config.implementation().name().toLowerCase(Locale.ROOT),
                    boardWidth(),
                    boardHeight(),
                    config.balls(),
                    config.threads(),
                    config.steps(),
                    config.seed(),
                    row.summary().meanThroughputStepsPerSecond()));
        }
        return List.copyOf(output);
    }

    private static List<SpeedupRow> buildSpeedupRows(List<DerivedRow> rows) {
        var output = new ArrayList<SpeedupRow>(rows.size());
        for (var row : rows) {
            BenchmarkConfig config = row.summary().config();
            output.add(new SpeedupRow(
                    config.implementation().name().toLowerCase(Locale.ROOT),
                    boardWidth(),
                    boardHeight(),
                    config.balls(),
                    config.threads(),
                    config.steps(),
                    config.seed(),
                    config.effectiveThreads(),
                    row.speedup()));
        }
        return List.copyOf(output);
    }

    private static List<CrossoverRow> buildCrossoverRows(List<DerivedRow> rows) {
        Map<CrossoverKey, List<DerivedRow>> grouped = new LinkedHashMap<>();
        for (var row : rows) {
            BenchmarkConfig config = row.summary().config();
            if (config.implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL) {
                continue;
            }
            grouped.computeIfAbsent(new CrossoverKey(config.implementation(), config.threads(), config.seed()), ignored -> new ArrayList<>()).add(row);
        }

        var output = new ArrayList<CrossoverRow>(grouped.size());
        for (var entry : grouped.entrySet()) {
            var candidateRows = entry.getValue();
            candidateRows.sort(Comparator.comparingInt(row -> row.summary().config().balls()));
            DerivedRow crossover = null;
            for (var row : candidateRows) {
                if (row.speedup() > SPEEDUP_THRESHOLD) {
                    crossover = row;
                    break;
                }
            }
            if (crossover == null) {
                continue;
            }
            BenchmarkConfig config = crossover.summary().config();
            output.add(new CrossoverRow(
                    config.implementation().name().toLowerCase(Locale.ROOT),
                    boardWidth(),
                    boardHeight(),
                    config.effectiveThreads(),
                    config.seed(),
                    config.balls(),
                    config.steps(),
                    crossover.summary().meanElapsedMillis() * 1_000_000.0,
                    crossover.summary().meanThroughputStepsPerSecond(),
                    crossover.speedup()));
        }
        output.sort(Comparator
                .comparing((CrossoverRow row) -> row.engineName)
                .thenComparingInt(row -> row.workerCount)
                .thenComparingLong(row -> row.seed)
                .thenComparingInt(row -> row.crossoverBalls));
        return List.copyOf(output);
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

    private static double boardWidth() {
        return BENCHMARK_BOARD_BOUNDARY.x1() - BENCHMARK_BOARD_BOUNDARY.x0();
    }

    private static double boardHeight() {
        return BENCHMARK_BOARD_BOUNDARY.y1() - BENCHMARK_BOARD_BOUNDARY.y0();
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

    private record ScenarioKey(int balls, int steps, long seed) {
    }

    private record CrossoverKey(BenchmarkConfig.ImplementationType implementation, int workerCount, long seed) {
    }

    private record DerivedRow(BenchmarkSummary summary, double speedup) {
    }

    private record MetadataRow(
            String timestampUtc,
            String gitCommitHash,
            int maxThreads,
            String osName,
            String osVersion,
            String osArch,
            String javaVersion,
            String jvmName,
            double boardWidth,
            double boardHeight,
            String engineName,
            int balls,
            int threads,
            int steps,
            long seed,
            int warmupRuns,
            int measuredRuns,
            String benchmarkConfig) implements CsvRow {

        @Override
        public String toCsv() {
            return csvRow(
                    timestampUtc,
                    gitCommitHash,
                    Integer.toString(maxThreads),
                    osName,
                    osVersion,
                    osArch,
                    javaVersion,
                    jvmName,
                    formatDouble(boardWidth),
                    formatDouble(boardHeight),
                    engineName,
                    Integer.toString(balls),
                    Integer.toString(threads),
                    Integer.toString(steps),
                    Long.toString(seed),
                    Integer.toString(warmupRuns),
                    Integer.toString(measuredRuns),
                    benchmarkConfig);
        }
    }

    private record AvgTickTimeRow(
            String engineName,
            double boardWidth,
            double boardHeight,
            int balls,
            int threads,
            int steps,
            long seed,
            double avgTickTimeNs,
            double minTickTimeNs,
            double maxTickTimeNs,
            double stdTickTimeNs,
            double throughputStepsPerSec) implements CsvRow {

        @Override
        public String toCsv() {
            return csvRow(
                    engineName,
                    formatDouble(boardWidth),
                    formatDouble(boardHeight),
                    Integer.toString(balls),
                    Integer.toString(threads),
                    Integer.toString(steps),
                    Long.toString(seed),
                    formatDouble(avgTickTimeNs),
                    formatDouble(minTickTimeNs),
                    formatDouble(maxTickTimeNs),
                    formatDouble(stdTickTimeNs),
                    formatDouble(throughputStepsPerSec));
        }
    }

    private record ThroughputRow(
            String engineName,
            double boardWidth,
            double boardHeight,
            int balls,
            int threads,
            int steps,
            long seed,
            double throughputStepsPerSec) implements CsvRow {

        @Override
        public String toCsv() {
            return csvRow(
                    engineName,
                    formatDouble(boardWidth),
                    formatDouble(boardHeight),
                    Integer.toString(balls),
                    Integer.toString(threads),
                    Integer.toString(steps),
                    Long.toString(seed),
                    formatDouble(throughputStepsPerSec));
        }
    }

    private record SpeedupRow(
            String engineName,
            double boardWidth,
            double boardHeight,
            int balls,
            int threads,
            int steps,
            long seed,
            int workerCount,
            double speedupVsSequential) implements CsvRow {

        @Override
        public String toCsv() {
            return csvRow(
                    engineName,
                    formatDouble(boardWidth),
                    formatDouble(boardHeight),
                    Integer.toString(balls),
                    Integer.toString(threads),
                    Integer.toString(steps),
                    Long.toString(seed),
                    Integer.toString(workerCount),
                    formatDouble(speedupVsSequential));
        }
    }

    private record CrossoverRow(
            String engineName,
            double boardWidth,
            double boardHeight,
            int workerCount,
            long seed,
            int crossoverBalls,
            int steps,
            double avgTickTimeNs,
            double throughputStepsPerSec,
            double speedupVsSequential) implements CsvRow {

        @Override
        public String toCsv() {
            return csvRow(
                    engineName,
                    formatDouble(boardWidth),
                    formatDouble(boardHeight),
                    Integer.toString(workerCount),
                    Long.toString(seed),
                    Integer.toString(crossoverBalls),
                    Integer.toString(steps),
                    formatDouble(avgTickTimeNs),
                    formatDouble(throughputStepsPerSec),
                    formatDouble(speedupVsSequential));
        }
    }

    /**
     * Paths of the exported benchmark result tables.
     *
     * @param metadataFile metadata CSV path
     * @param avgTickTimeFile average tick time CSV path
     * @param throughputFile throughput CSV path
     * @param speedupFile speedup CSV path
     * @param crossoverFile crossover CSV path
     */
    public record ExportedResults(
            Path metadataFile,
            Path avgTickTimeFile,
            Path throughputFile,
            Path speedupFile,
            Path crossoverFile) {
    }
}
