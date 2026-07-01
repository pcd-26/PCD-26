package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkRunDirectoriesTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesUniqueRunDirectoriesWithoutOverwritingExistingRuns() throws Exception {
        Instant timestamp = Instant.parse("2026-06-21T13:15:30Z");
        Path first = BenchmarkRunDirectories.resolveRunDirectory(tempDir, timestamp);
        Files.createDirectories(first);

        Path second = BenchmarkRunDirectories.resolveRunDirectory(tempDir, timestamp);

        assertTrue(first.endsWith("run-20260621-131530-000"));
        assertTrue(second.endsWith("run-20260621-131530-000-1"));
    }
}
