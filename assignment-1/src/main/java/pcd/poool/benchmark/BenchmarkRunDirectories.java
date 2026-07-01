package pcd.poool.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Resolves deterministic run-specific directories under the benchmark root.
 */
final class BenchmarkRunDirectories {

    private static final DateTimeFormatter RUN_DIRECTORY_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private BenchmarkRunDirectories() {
    }

    static Path resolveRunDirectory(Path root, Instant timestamp) {
        String baseName = "run-" + RUN_DIRECTORY_FORMAT.format(timestamp);
        Path candidate = root.resolve(baseName);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = root.resolve(baseName + "-" + suffix++);
        }
        return candidate;
    }
}
