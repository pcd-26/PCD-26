package pcd.poool.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
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
        assertTrue(threadedConfig.contains("classpath=target/jpf-classes"));
        assertTrue(taskConfig.contains("classpath=target/jpf-classes"));
    }

    @Test
    void minimalJpfModelsRunUnderJpfWhenClasspathIsProvided() throws Exception {
        String jpfClasspath = firstNonBlank(System.getProperty("jpf.cp"), System.getenv("JPF_CP"));
        assumeTrue(jpfClasspath != null && !jpfClasspath.isBlank(),
                "Set JPF_CP to the classpath that exposes gov.nasa.jpf.tool.RunJPF");

        compileMinimalHarnesses();

        runJpf("threaded-minimal.jpf", jpfClasspath);
        runJpf("taskbased-minimal.jpf", jpfClasspath);
    }

    private static void compileMinimalHarnesses() throws IOException {
        Path sourceDir = Path.of("verification", "jpf", "src");
        Path outputDir = Path.of("target", "jpf-classes");

        deleteDirectoryIfPresent(outputDir);
        Files.createDirectories(outputDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "A JDK with javac is required to compile the JPF harnesses");

        List<String> sourceFiles = new ArrayList<>();
        try (var paths = Files.walk(sourceDir)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> sourceFiles.add(path.toString()));
        }

        assertTrue(!sourceFiles.isEmpty(), "No JPF harness sources were found");

        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(outputDir.toString());
        args.add("--release");
        args.add("11");
        args.addAll(sourceFiles);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode = compiler.run(null, new PrintStream(stdout), new PrintStream(stderr), args.toArray(String[]::new));
        assertEquals(0, exitCode, () -> formatFailure("javac", stdout, stderr));
    }

    private static void runJpf(String configFile, String jpfClasspath) throws IOException, InterruptedException {
        String launcher = firstNonBlank(
                System.getProperty("jpf.launcher"),
                System.getenv("JPF_LAUNCHER"),
                "gov.nasa.jpf.tool.RunJPF");
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        Path configPath = Path.of("verification", "jpf", configFile);

        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable,
                "-cp",
                jpfClasspath,
                launcher,
                configPath.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var input = process.getInputStream()) {
            input.transferTo(output);
        }

        boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("JPF did not terminate within the time limit for " + configFile);
        }

        int exitCode = process.exitValue();
        assertEquals(0, exitCode, () -> formatFailure("JPF " + configFile, output, null));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void deleteDirectoryIfPresent(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(current -> {
                        try {
                            Files.deleteIfExists(current);
                        } catch (IOException ex) {
                            throw new IllegalStateException("failed to clean " + current, ex);
                        }
                    });
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String formatFailure(String phase, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr) {
        StringBuilder message = new StringBuilder(phase).append(" failed");
        if (stdout != null && stdout.size() > 0) {
            message.append(System.lineSeparator()).append("stdout:").append(System.lineSeparator())
                    .append(stdout.toString(StandardCharsets.UTF_8));
        }
        if (stderr != null && stderr.size() > 0) {
            message.append(System.lineSeparator()).append("stderr:").append(System.lineSeparator())
                    .append(stderr.toString(StandardCharsets.UTF_8));
        }
        return message.toString();
    }
}
