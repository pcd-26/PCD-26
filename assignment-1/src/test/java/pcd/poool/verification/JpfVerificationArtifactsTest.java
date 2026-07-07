package pcd.poool.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JpfVerificationArtifactsTest {

    @Test
    void jpfVerificationAreaContainsTheExpectedHarnessesAndConfigs() throws IOException {
        Path verificationDir = Path.of("verification", "jpf");

        assertTrue(Files.isRegularFile(verificationDir.resolve("README.md")));
        assertTrue(Files.isRegularFile(verificationDir.resolve("threaded-minimal.jpf")));
        assertTrue(Files.isRegularFile(verificationDir.resolve("taskbased-minimal.jpf")));
        assertTrue(Files.isRegularFile(verificationDir.resolve("src")
                .resolve("pcd")
                .resolve("poool")
                .resolve("verification")
                .resolve("jpf")
                .resolve("ThreadedMiniHarness.java")));
        assertTrue(Files.isRegularFile(verificationDir.resolve("src")
                .resolve("pcd")
                .resolve("poool")
                .resolve("verification")
                .resolve("jpf")
                .resolve("TaskBasedMiniHarness.java")));

        String threadedConfig = Files.readString(verificationDir.resolve("threaded-minimal.jpf"), StandardCharsets.UTF_8);
        String taskConfig = Files.readString(verificationDir.resolve("taskbased-minimal.jpf"), StandardCharsets.UTF_8);

        assertTrue(threadedConfig.contains("target=pcd.poool.verification.jpf.ThreadedMiniHarness"));
        assertTrue(taskConfig.contains("target=pcd.poool.verification.jpf.TaskBasedMiniHarness"));
        assertTrue(threadedConfig.contains("classpath=verification/jpf/src"));
        assertTrue(taskConfig.contains("classpath=verification/jpf/src"));
    }
}
