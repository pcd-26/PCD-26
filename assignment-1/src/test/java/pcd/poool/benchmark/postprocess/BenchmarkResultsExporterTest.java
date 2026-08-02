package pcd.poool.benchmark.postprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pcd.poool.benchmark.config.BenchmarkConfig;
import pcd.poool.benchmark.core.BenchmarkRunResult;
import pcd.poool.benchmark.core.BenchmarkRunner;
import pcd.poool.benchmark.core.BenchmarkSummary;
import pcd.poool.benchmark.core.RuntimeTelemetry;

class BenchmarkResultsExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsSummaryTablesAndRuntimeMetadata() throws Exception {
        var telemetry = new RuntimeTelemetry(
                8,
                "Test JVM",
                "21",
                "Test OS",
                "1.0",
                "amd64");

        var sequential100 = summary(
                BenchmarkConfig.ImplementationType.SEQUENTIAL,
                100,
                1,
                10,
                42L,
                10_000_000L,
                1_000L);
        var sequential500 = summary(
                BenchmarkConfig.ImplementationType.SEQUENTIAL,
                500,
                1,
                10,
                42L,
                10_000_000L,
                1_000L);
        var threaded100 = summary(
                BenchmarkConfig.ImplementationType.THREADS,
                100,
                2,
                10,
                42L,
                20_000_000L,
                500L);
        var threaded500 = summary(
                BenchmarkConfig.ImplementationType.THREADS,
                500,
                2,
                10,
                42L,
                4_000_000L,
                2_500L);

        var exported = BenchmarkResultsExporter.export(
                tempDir,
                Instant.parse("2026-06-21T13:15:30Z"),
                telemetry,
                "deadbeefcafebabe",
                List.of(sequential100, sequential500, threaded100, threaded500));

        assertEquals(tempDir.resolve(BenchmarkResultsExporter.METADATA_FILE_NAME), exported.metadataFile());
        assertEquals(tempDir.resolve(BenchmarkResultsExporter.AVG_TICK_TIME_FILE_NAME), exported.avgTickTimeFile());
        assertEquals(tempDir.resolve(BenchmarkResultsExporter.CROSSOVER_FILE_NAME), exported.crossoverFile());

        var metadataLines = Files.readAllLines(exported.metadataFile());
        var avgTickLines = Files.readAllLines(exported.avgTickTimeFile());
        var speedupLines = Files.readAllLines(exported.speedupFile());
        var crossoverLines = Files.readAllLines(exported.crossoverFile());

        assertEquals("timestamp_utc,git_commit_hash,max_threads,os_name,os_version,os_arch,java_version,jvm_name,board_width,board_height,implementation,balls,threads,steps,seed,warmup_runs,measured_runs,benchmark_config", metadataLines.get(0));
        assertTrue(metadataLines.get(1).contains("deadbeefcafebabe"));
        assertTrue(metadataLines.get(1).contains("implementation=sequential balls=100 threads=1 steps=10 seed=42"));
        assertEquals("engine_name,board_width,board_height,balls,threads,steps,seed,avg_tick_time_ns,min_tick_time_ns,max_tick_time_ns,std_tick_time_ns,throughput_steps_per_sec", avgTickLines.get(0));
        assertTrue(avgTickLines.stream().anyMatch(line -> line.startsWith("sequential,3.000000,2.000000,100,1,10,42,10000000.000000,10000000.000000,10000000.000000,0.000000,1000.000000")));
        assertTrue(speedupLines.stream().anyMatch(line -> line.startsWith("threads,3.000000,2.000000,500,2,10,42,2,2.500000")));
        assertEquals(2, crossoverLines.size());
        assertTrue(crossoverLines.get(1).startsWith("threads,3.000000,2.000000,2,42,500,10,4000000.000000,2500.000000,2.500000"));
    }

    private static BenchmarkSummary summary(
            BenchmarkConfig.ImplementationType implementation,
            int balls,
            int threads,
            int steps,
            long seed,
            long elapsedNanos,
            long checksum) {
        var config = new BenchmarkConfig(
                implementation,
                balls,
                threads,
                steps,
                seed,
                0,
                1,
                false,
                false,
                Path.of("target"));
        var result = BenchmarkRunResult.success(1, false, elapsedNanos, steps, checksum);
        return BenchmarkRunner.summarize(config, List.of(result));
    }
}
