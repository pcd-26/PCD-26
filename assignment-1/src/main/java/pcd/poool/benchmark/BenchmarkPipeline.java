package pcd.poool.benchmark;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Runs the full benchmark pipeline in one command.
 */
public final class BenchmarkPipeline {

    private static final Path DEFAULT_RESULTS_ROOT = Path.of("benchmark", "results");
    private static final Path DEFAULT_CHARTS_ROOT = Path.of("benchmark", "charts");
    private static final DateTimeFormatter DIRECTORY_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private BenchmarkPipeline() {
    }

    /**
     * Runs the full benchmark pipeline from the command line.
     *
     * @param args optional CLI arguments
     */
    public static void main(String[] args) {
        try {
            var request = parseArgs(args);
            if (request == null) {
                return;
            }
            var report = run(request);
            System.out.printf(Locale.US,
                    "benchmark_pipeline_completed results_dir=%s charts_dir=%s%n",
                    report.resultsDir(),
                    report.chartsDir());
        } catch (Exception ex) {
            System.err.printf(Locale.US, "benchmark_pipeline_failed message=%s%n", ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /**
     * Runs the benchmark pipeline using the default benchmark steps.
     *
     * @param request pipeline request
     * @return execution report
     * @throws Exception if any benchmark or chart step fails
     */
    public static BenchmarkPipelineReport run(BenchmarkPipelineRequest request) throws Exception {
        return run(request, BenchmarkPipelineSteps.defaultSteps());
    }

    static BenchmarkPipelineReport run(BenchmarkPipelineRequest request, BenchmarkPipelineSteps steps) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(steps, "steps");

        Path resultsDir = request.resultsRoot().resolve(DIRECTORY_FORMATTER.format(request.timestamp()));
        Files.createDirectories(resultsDir);
        Files.createDirectories(request.chartsRoot());

        printStep(request.out(), "headless-benchmark-start", resultsDir);
        var headlessRequest = HeadlessBenchmarkRunner.defaults()
                .withOutputFile(resultsDir.resolve("raw-results.csv"));
        var headlessReport = steps.runHeadless(headlessRequest);
        printStep(request.out(), "headless-benchmark-complete", headlessReport.outputFile());

        printStep(request.out(), "suite-start", resultsDir);
        var suiteReport = steps.runSuite(request.resultsRoot(), request.timestamp());
        printStep(request.out(), "suite-complete", suiteReport.outputDir());

        printStep(request.out(), "scalability-benchmark-start", resultsDir);
        var scalabilityRequest = ScalabilityBenchmarkRunner.defaults()
                .withOutputFile(resultsDir.resolve("raw-scalability-results.csv"));
        var scalabilityReport = steps.runScalability(scalabilityRequest);
        printStep(request.out(), "scalability-benchmark-complete", scalabilityReport.outputFile());

        printStep(request.out(), "gui-benchmark-start", resultsDir);
        var guiRequest = GuiResponsivenessBenchmarkRunner.defaults()
                .withOutputFile(resultsDir.resolve("raw-gui-results.csv"));
        var guiReport = steps.runGui(guiRequest);
        Path guiCompatibilityFile = resultsDir.resolve("gui-responsiveness.csv");
        Files.copy(guiReport.outputFile(), guiCompatibilityFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        printStep(request.out(), "gui-benchmark-complete", guiReport.outputFile());

        printStep(request.out(), "chart-generation-start", request.chartsRoot());
        steps.generateCharts(resultsDir, request.chartsRoot());
        printStep(request.out(), "chart-generation-complete", request.chartsRoot());

        return new BenchmarkPipelineReport(
                resultsDir,
                request.chartsRoot(),
                headlessReport.outputFile(),
                headlessReport.aggregatedOutputFile(),
                headlessReport.speedupOutputFile(),
                suiteReport.outputDir(),
                scalabilityReport.outputFile(),
                scalabilityReport.aggregatedOutputFile(),
                guiReport.outputFile(),
                guiReport.aggregatedOutputFile(),
                guiCompatibilityFile);
    }

    private static void printStep(PrintStream out, String label, Path path) {
        out.printf(Locale.US, "%s path=%s%n", label, path);
    }

    private static BenchmarkPipelineRequest parseArgs(String[] args) {
        Path resultsRoot = DEFAULT_RESULTS_ROOT;
        Path chartsRoot = DEFAULT_CHARTS_ROOT;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                return null;
            }
            int equals = arg.indexOf('=');
            String key;
            String value;
            if (equals >= 0) {
                key = arg.substring(0, equals);
                value = arg.substring(equals + 1);
            } else {
                key = arg;
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + key);
                }
                value = args[++i];
            }
            switch (key) {
                case "--results-root" -> resultsRoot = Path.of(value);
                case "--charts-root" -> chartsRoot = Path.of(value);
                default -> throw new IllegalArgumentException("unknown option: " + key);
            }
        }
        return BenchmarkPipelineRequest.defaults(resultsRoot, chartsRoot, Instant.now(), System.out);
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java -cp assignment-1/target/classes pcd.poool.benchmark.BenchmarkPipeline \
                  [--results-root benchmark/results] \
                  [--charts-root benchmark/charts]
                """);
    }

    /**
     * Pipeline request parameters.
     *
     * @param resultsRoot root directory for benchmark CSV outputs
     * @param chartsRoot directory where charts will be written
     * @param timestamp instant used to name the output directory
     * @param out progress stream
     */
    public record BenchmarkPipelineRequest(Path resultsRoot, Path chartsRoot, Instant timestamp, PrintStream out) {

        public BenchmarkPipelineRequest {
            if (resultsRoot == null) {
                throw new IllegalArgumentException("resultsRoot must not be null");
            }
            if (chartsRoot == null) {
                throw new IllegalArgumentException("chartsRoot must not be null");
            }
            if (timestamp == null) {
                throw new IllegalArgumentException("timestamp must not be null");
            }
            if (out == null) {
                throw new IllegalArgumentException("out must not be null");
            }
        }

        public static BenchmarkPipelineRequest defaults(Path resultsRoot, Path chartsRoot, Instant timestamp, PrintStream out) {
            return new BenchmarkPipelineRequest(resultsRoot, chartsRoot, timestamp, out);
        }
    }

    /**
     * Report produced by the benchmark pipeline.
     *
     * @param resultsDir benchmark results directory
     * @param chartsDir generated charts directory
     * @param headlessRawFile headless raw CSV
     * @param headlessAggregatedFile headless aggregated CSV
     * @param headlessSpeedupFile headless speedup CSV
     * @param suiteDir suite output directory
     * @param scalabilityRawFile scalability raw CSV
     * @param scalabilityAggregatedFile scalability aggregated CSV
     * @param guiRawFile GUI raw CSV
     * @param guiAggregatedFile GUI aggregated CSV
     * @param guiCompatibilityFile GUI compatibility CSV used by chart generation
     */
    public record BenchmarkPipelineReport(
            Path resultsDir,
            Path chartsDir,
            Path headlessRawFile,
            Path headlessAggregatedFile,
            Path headlessSpeedupFile,
            Path suiteDir,
            Path scalabilityRawFile,
            Path scalabilityAggregatedFile,
            Path guiRawFile,
            Path guiAggregatedFile,
            Path guiCompatibilityFile) {
    }

    /**
     * Step bundle used by the default pipeline.
     */
    interface BenchmarkPipelineSteps {

        HeadlessBenchmarkRunner.BenchmarkReport runHeadless(HeadlessBenchmarkRunner.BenchmarkRequest request) throws IOException;

        BenchmarkSuite.SuiteReport runSuite(Path resultsRoot, Instant timestamp) throws Exception;

        ScalabilityBenchmarkRunner.BenchmarkReport runScalability(ScalabilityBenchmarkRunner.BenchmarkRequest request) throws IOException;

        GuiResponsivenessBenchmarkRunner.BenchmarkReport runGui(GuiResponsivenessBenchmarkRunner.BenchmarkRequest request) throws IOException;

        void generateCharts(Path inputDir, Path outputDir) throws IOException, InterruptedException;

        static BenchmarkPipelineSteps defaultSteps() {
            return new DefaultBenchmarkPipelineSteps();
        }
    }

    private static final class DefaultBenchmarkPipelineSteps implements BenchmarkPipelineSteps {

        @Override
        public HeadlessBenchmarkRunner.BenchmarkReport runHeadless(HeadlessBenchmarkRunner.BenchmarkRequest request) throws IOException {
            return HeadlessBenchmarkRunner.run(request);
        }

        @Override
        public BenchmarkSuite.SuiteReport runSuite(Path resultsRoot, Instant timestamp) throws Exception {
            return BenchmarkSuite.run(resultsRoot, timestamp, System.out, System.err, BenchmarkSuite.Mode.FULL);
        }

        @Override
        public ScalabilityBenchmarkRunner.BenchmarkReport runScalability(ScalabilityBenchmarkRunner.BenchmarkRequest request) throws IOException {
            return ScalabilityBenchmarkRunner.run(request);
        }

        @Override
        public GuiResponsivenessBenchmarkRunner.BenchmarkReport runGui(GuiResponsivenessBenchmarkRunner.BenchmarkRequest request) throws IOException {
            try {
                return GuiResponsivenessBenchmarkRunner.run(request);
            } catch (Exception ex) {
                if (ex instanceof IOException ioException) {
                    throw ioException;
                }
                throw new IllegalStateException("failed to run GUI benchmark", ex);
            }
        }

        @Override
        public void generateCharts(Path inputDir, Path outputDir) throws IOException, InterruptedException {
            Path script = Path.of("scripts", "plot_benchmarks.py").toAbsolutePath().normalize();
            var process = new ProcessBuilder("python", script.toString(), "--input-dir", inputDir.toString(), "--output-dir", outputDir.toString())
                    .redirectErrorStream(true)
                    .start();
            try (var in = process.getInputStream()) {
                in.transferTo(System.err);
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("chart generation failed with exit code " + exit);
            }
        }
    }
}
