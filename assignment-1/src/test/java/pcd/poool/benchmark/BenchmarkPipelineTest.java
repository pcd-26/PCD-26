package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void runsAllStepsInOrderAndProducesExpectedFiles() throws Exception {
        var events = new ArrayList<String>();
        var out = new PrintStream(new ByteArrayOutputStream(), true);
        var request = new BenchmarkPipeline.BenchmarkPipelineRequest(
                tempDir.resolve("results"),
                tempDir.resolve("charts"),
                Instant.parse("2026-06-21T13:15:30Z"),
                out);

        var report = BenchmarkPipeline.run(request, new BenchmarkPipeline.BenchmarkPipelineSteps() {
            @Override
            public HeadlessBenchmarkRunner.BenchmarkReport runHeadless(HeadlessBenchmarkRunner.BenchmarkRequest benchmarkRequest) throws IOException {
                events.add("headless");
                Files.createDirectories(benchmarkRequest.outputFile().getParent());
                Files.writeString(benchmarkRequest.outputFile(), "headless");
                var aggregated = benchmarkRequest.outputFile().getParent().resolve("aggregated-results.csv");
                var speedup = benchmarkRequest.outputFile().getParent().resolve("speedup-results.csv");
                Files.writeString(aggregated, "agg");
                Files.writeString(speedup, "speedup");
                return new HeadlessBenchmarkRunner.BenchmarkReport(benchmarkRequest.outputFile(), aggregated, speedup, List.of());
            }

            @Override
            public BenchmarkSuite.SuiteReport runSuite(Path resultsRoot, Instant timestamp) throws Exception {
                events.add("suite");
                Path outputDir = resultsRoot.resolve("20260621-131530-000");
                Files.createDirectories(outputDir);
                Files.writeString(outputDir.resolve(BenchmarkCsvWriter.SUMMARY_FILE_NAME), "summary");
                Files.writeString(outputDir.resolve(BenchmarkCsvWriter.RUNS_FILE_NAME), "runs");
                Files.writeString(outputDir.resolve(RuntimeTelemetryCsvWriter.ENVIRONMENT_FILE_NAME), "env");
                return new BenchmarkSuite.SuiteReport(outputDir, 1, 0);
            }

            @Override
            public ScalabilityBenchmarkRunner.BenchmarkReport runScalability(ScalabilityBenchmarkRunner.BenchmarkRequest benchmarkRequest) throws IOException {
                events.add("scalability");
                Files.createDirectories(benchmarkRequest.outputFile().getParent());
                Files.writeString(benchmarkRequest.outputFile(), "scalability");
                var aggregated = benchmarkRequest.outputFile().getParent().resolve("aggregated-scalability-results.csv");
                Files.writeString(aggregated, "agg");
                return new ScalabilityBenchmarkRunner.BenchmarkReport(benchmarkRequest.outputFile(), aggregated, List.of());
            }

            @Override
            public GuiResponsivenessBenchmarkRunner.BenchmarkReport runGui(GuiResponsivenessBenchmarkRunner.BenchmarkRequest benchmarkRequest) throws IOException {
                events.add("gui");
                Files.createDirectories(benchmarkRequest.outputFile().getParent());
                Files.writeString(benchmarkRequest.outputFile(), "gui-raw");
                var aggregated = benchmarkRequest.outputFile().getParent().resolve("aggregated-gui-results.csv");
                Files.writeString(aggregated, "gui-agg");
                return new GuiResponsivenessBenchmarkRunner.BenchmarkReport(benchmarkRequest.outputFile(), aggregated, List.of());
            }

            @Override
            public void generateCharts(Path inputDir, Path outputDir) throws IOException {
                events.add("charts");
                Files.createDirectories(outputDir);
                Files.writeString(outputDir.resolve("chart.txt"), inputDir.toString());
            }
        });

        Path resultsDir = tempDir.resolve("results").resolve("20260621-131530-000");
        assertEquals(resultsDir, report.resultsDir());
        assertEquals(tempDir.resolve("charts"), report.chartsDir());
        assertEquals(List.of("headless", "suite", "scalability", "gui", "charts"), events);
        assertTrue(Files.exists(resultsDir.resolve("raw-results.csv")));
        assertTrue(Files.exists(resultsDir.resolve("raw-scalability-results.csv")));
        assertTrue(Files.exists(resultsDir.resolve("raw-gui-results.csv")));
        assertTrue(Files.exists(resultsDir.resolve("gui-responsiveness.csv")));
        assertTrue(Files.exists(resultsDir.resolve(BenchmarkScalabilityAnalyzer.SPEEDUP_TABLE_FILE_NAME)));
        assertTrue(Files.exists(resultsDir.resolve(BenchmarkScalabilityAnalyzer.EFFICIENCY_TABLE_FILE_NAME)));
        assertTrue(Files.exists(resultsDir.resolve(BenchmarkScalabilityAnalyzer.SCALABILITY_TABLE_FILE_NAME)));
        assertTrue(Files.exists(tempDir.resolve("charts").resolve("chart.txt")));
        assertFalse(Files.exists(resultsDir.resolve("missing.txt")));
    }

    @Test
    void failsFastWhenAnEarlierStepThrows() {
        var events = new ArrayList<String>();
        var request = new BenchmarkPipeline.BenchmarkPipelineRequest(
                tempDir.resolve("results"),
                tempDir.resolve("charts"),
                Instant.parse("2026-06-21T13:15:30Z"),
                new PrintStream(new ByteArrayOutputStream(), true));

        var ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> BenchmarkPipeline.run(request, new BenchmarkPipeline.BenchmarkPipelineSteps() {
            @Override
            public HeadlessBenchmarkRunner.BenchmarkReport runHeadless(HeadlessBenchmarkRunner.BenchmarkRequest benchmarkRequest) {
                events.add("headless");
                throw new IllegalStateException("boom");
            }

            @Override
            public BenchmarkSuite.SuiteReport runSuite(Path resultsRoot, Instant timestamp) {
                events.add("suite");
                throw new IllegalStateException("should not run");
            }

            @Override
            public ScalabilityBenchmarkRunner.BenchmarkReport runScalability(ScalabilityBenchmarkRunner.BenchmarkRequest benchmarkRequest) {
                events.add("scalability");
                throw new IllegalStateException("should not run");
            }

            @Override
            public GuiResponsivenessBenchmarkRunner.BenchmarkReport runGui(GuiResponsivenessBenchmarkRunner.BenchmarkRequest benchmarkRequest) {
                events.add("gui");
                throw new IllegalStateException("should not run");
            }

            @Override
            public void generateCharts(Path inputDir, Path outputDir) {
                events.add("charts");
                throw new IllegalStateException("should not run");
            }
        }));

        assertTrue(ex.getMessage().contains("boom"));
        assertEquals(List.of("headless"), events);
    }
}
