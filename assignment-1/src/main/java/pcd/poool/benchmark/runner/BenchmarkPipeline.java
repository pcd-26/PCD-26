package pcd.poool.benchmark;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Runs the full benchmark pipeline in one command.
 */
public final class BenchmarkPipeline {

    private static final Path DEFAULT_RESULTS_ROOT = defaultAssignmentPath("benchmarks", "results");
    private static final Path DEFAULT_CHARTS_ROOT = defaultAssignmentPath("benchmarks", "charts");

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
                    "benchmark_pipeline_completed mode=%s results_dir=%s charts_dir=%s%n",
                    request.mode(),
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

        prepareOutputRoots(request.resultsRoot(), request.chartsRoot());
        Path resultsDir = request.resultsRoot();

        var headlessRequest = buildHeadlessRequest(request.mode(), resultsDir);

        logStep(request.out(), "headless-benchmark-start", resultsDir);
        var headlessReport = steps.runHeadless(headlessRequest);
        logStep(request.out(), "headless-benchmark-complete", headlessReport.outputFile());

        if (request.mode() == Mode.SPEEDUP) {
            return runSpeedupPipeline(request, steps, resultsDir, headlessReport);
        }

        return runFullPipeline(request, steps, resultsDir, headlessReport);
    }

    private static BenchmarkPipelineReport runSpeedupPipeline(
            BenchmarkPipelineRequest request,
            BenchmarkPipelineSteps steps,
            Path resultsDir,
            HeadlessBenchmarkRunner.BenchmarkReport headlessReport) throws Exception {

        logStep(request.out(), "scalability-benchmark-start", resultsDir);
        var scalabilityRequest = buildScalabilityRequest(resultsDir);
        var scalabilityReport = steps.runScalability(scalabilityRequest);
        logStep(request.out(), "scalability-benchmark-complete", scalabilityReport.outputFile());

        logStep(request.out(), "chart-generation-start", request.chartsRoot());
        steps.generateCharts(resultsDir, request.chartsRoot(), request.profile());
        logStep(request.out(), "chart-generation-complete", request.chartsRoot());
        return new BenchmarkPipelineReport(
                resultsDir,
                request.chartsRoot(),
                headlessReport.outputFile(),
                headlessReport.aggregatedOutputFile(),
                headlessReport.speedupOutputFile(),
                null,
                scalabilityReport.outputFile(),
                scalabilityReport.aggregatedOutputFile(),
                null,
                null,
                null);
    }

    private static BenchmarkPipelineReport runFullPipeline(
            BenchmarkPipelineRequest request,
            BenchmarkPipelineSteps steps,
            Path resultsDir,
            HeadlessBenchmarkRunner.BenchmarkReport headlessReport) throws Exception {

        logStep(request.out(), "suite-start", resultsDir);
        var suiteReport = steps.runSuite(request.resultsRoot());
        logStep(request.out(), "suite-complete", suiteReport.outputDir());

        logStep(request.out(), "scalability-benchmark-start", resultsDir);
        var scalabilityRequest = buildScalabilityRequest(resultsDir);
        var scalabilityReport = steps.runScalability(scalabilityRequest);
        logStep(request.out(), "scalability-benchmark-complete", scalabilityReport.outputFile());

        logStep(request.out(), "chart-generation-start", request.chartsRoot());
        steps.generateCharts(resultsDir, request.chartsRoot(), request.profile());
        logStep(request.out(), "chart-generation-complete", request.chartsRoot());

        return new BenchmarkPipelineReport(
                resultsDir,
                request.chartsRoot(),
                headlessReport.outputFile(),
                headlessReport.aggregatedOutputFile(),
                headlessReport.speedupOutputFile(),
                suiteReport.outputDir(),
                scalabilityReport.outputFile(),
                scalabilityReport.aggregatedOutputFile(),
                null,
                null,
                null);
    }

    private static void logStep(PrintStream out, String label, Path path) {
        out.printf(Locale.US, "%s path=%s%n", label, path);
    }

    private static HeadlessBenchmarkRunner.BenchmarkRequest buildHeadlessRequest(Mode mode, Path resultsDir) {
        var headlessRequest = mode == Mode.SPEEDUP
                ? HeadlessBenchmarkRunner.speedupGateDefaults()
                : HeadlessBenchmarkRunner.defaults();
        return headlessRequest.withOutputFile(resultsDir.resolve("raw-results.csv"));
    }

    private static ScalabilityBenchmarkRunner.BenchmarkRequest buildScalabilityRequest(Path resultsDir) {
        return ScalabilityBenchmarkRunner.defaults()
                .withOutputFile(resultsDir.resolve("raw-scalability-results.csv"));
    }

    private static void prepareOutputRoots(Path resultsRoot, Path chartsRoot) throws IOException {
        resetDirectory(resultsRoot);
        resetDirectory(chartsRoot);
        Files.createDirectories(resultsRoot);
        Files.createDirectories(chartsRoot);
    }

    private static BenchmarkPipelineRequest parseArgs(String[] args) {
        Path resultsRoot = DEFAULT_RESULTS_ROOT;
        Path chartsRoot = DEFAULT_CHARTS_ROOT;
        Mode mode = Mode.FULL;
        String profile = "full";
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
                case "--mode" -> mode = Mode.parse(value);
                case "--results-root" -> resultsRoot = Path.of(value);
                case "--charts-root" -> chartsRoot = Path.of(value);
                case "--profile" -> profile = value;
                default -> throw new IllegalArgumentException("unknown option: " + key);
            }
        }
        return BenchmarkPipelineRequest.defaults(resultsRoot, chartsRoot, mode, profile, Instant.now(), System.out);
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java -cp assignment-1/target/classes pcd.poool.benchmark.BenchmarkPipeline \
                  [--mode full|speedup] \
                  [--profile full|speedup] \
                  [--results-root benchmarks/results] \
                  [--charts-root benchmarks/charts]
                """);
    }

    private static Path defaultAssignmentPath(String... segments) {
        Path assignmentRoot = Path.of("assignment-1");
        if (Files.isDirectory(assignmentRoot)) {
            return assignmentRoot.resolve(Path.of("", segments));
        }
        return Path.of("", segments);
    }

    private static void resetDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .filter(path -> !path.equals(directory))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            throw new IllegalStateException("failed to clear directory: " + directory, ex);
                        }
                    });
        }
    }

    /**
     * Pipeline request parameters.
     *
     * @param resultsRoot root directory for benchmark CSV outputs
     * @param chartsRoot directory where charts will be written
     * @param mode pipeline execution mode
     * @param timestamp run timestamp preserved as benchmark metadata
     * @param out progress stream
     */
    public record BenchmarkPipelineRequest(Path resultsRoot, Path chartsRoot, Mode mode, String profile, Instant timestamp, PrintStream out) {

        public BenchmarkPipelineRequest(Path resultsRoot, Path chartsRoot, Instant timestamp, PrintStream out) {
            this(resultsRoot, chartsRoot, Mode.FULL, "full", timestamp, out);
        }

        public BenchmarkPipelineRequest {
            if (resultsRoot == null) {
                throw new IllegalArgumentException("resultsRoot must not be null");
            }
            if (chartsRoot == null) {
                throw new IllegalArgumentException("chartsRoot must not be null");
            }
            if (mode == null) {
                throw new IllegalArgumentException("mode must not be null");
            }
            if (profile == null) {
                throw new IllegalArgumentException("profile must not be null");
            }
            if (timestamp == null) {
                throw new IllegalArgumentException("timestamp must not be null");
            }
            if (out == null) {
                throw new IllegalArgumentException("out must not be null");
            }
        }

        public static BenchmarkPipelineRequest defaults(Path resultsRoot, Path chartsRoot, Mode mode, String profile, Instant timestamp, PrintStream out) {
            return new BenchmarkPipelineRequest(resultsRoot, chartsRoot, mode, profile, timestamp, out);
        }
    }

    /**
     * Pipeline execution mode.
     */
    public enum Mode {
        FULL,
        SPEEDUP;

        static Mode parse(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "full" -> FULL;
                case "speedup", "minimal", "perf", "gate" -> SPEEDUP;
                default -> throw new IllegalArgumentException("unknown pipeline mode: " + value);
            };
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
     * @param guiRawFile GUI raw CSV, if produced
     * @param guiAggregatedFile GUI aggregated CSV, if produced
     * @param guiCompatibilityFile GUI compatibility CSV used by chart generation, if produced
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

        BenchmarkSuite.SuiteReport runSuite(Path resultsRoot) throws Exception;

        ScalabilityBenchmarkRunner.BenchmarkReport runScalability(ScalabilityBenchmarkRunner.BenchmarkRequest request) throws IOException;

        void generateCharts(Path inputDir, Path outputDir, String profile) throws IOException, InterruptedException;

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
        public BenchmarkSuite.SuiteReport runSuite(Path resultsRoot) throws Exception {
            return BenchmarkSuite.run(resultsRoot, System.out, System.err, BenchmarkSuite.Mode.FULL);
        }

        @Override
        public ScalabilityBenchmarkRunner.BenchmarkReport runScalability(ScalabilityBenchmarkRunner.BenchmarkRequest request) throws IOException {
            return ScalabilityBenchmarkRunner.run(request);
        }

        @Override
        public void generateCharts(Path inputDir, Path outputDir, String profile) throws IOException, InterruptedException {
            Path script = resolvePlotScript();
            var process = new ProcessBuilder("python", script.toString(), "--input-dir", inputDir.toString(), "--output-dir", outputDir.toString(), "--profile", profile)
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

        private static Path resolvePlotScript() {
            Path repoRootStyle = Path.of("assignment-1", "scripts", "plot_benchmarks.py");
            if (Files.exists(repoRootStyle)) {
                return repoRootStyle.toAbsolutePath().normalize();
            }
            Path assignmentLocal = Path.of("scripts", "plot_benchmarks.py");
            if (Files.exists(assignmentLocal)) {
                return assignmentLocal.toAbsolutePath().normalize();
            }
            throw new IllegalStateException("plot_benchmarks.py not found in assignment-1/scripts or scripts");
        }
    }
}
