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
        assertTrue(Files.isRegularFile(verificationDir.resolve("bootstrap_jpf.py")));
        assertTrue(Files.isRegularFile(verificationDir.resolve("run_jpf.py")));
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
        String bootstrapScript = Files.readString(verificationDir.resolve("bootstrap_jpf.py"), StandardCharsets.UTF_8);
        String runScript = Files.readString(verificationDir.resolve("run_jpf.py"), StandardCharsets.UTF_8);

        assertTrue(threadedConfig.contains("target=pcd.poool.verification.jpf.ThreadedMiniHarness"));
        assertTrue(taskConfig.contains("target=pcd.poool.verification.jpf.TaskBasedMiniHarness"));
        assertTrue(threadedConfig.contains("classpath=target/jpf-classes"));
        assertTrue(taskConfig.contains("classpath=target/jpf-classes"));
        assertTrue(threadedConfig.contains("vm.storage.class=gov.nasa.jpf.vm.JenkinsStateSet"));
        assertTrue(taskConfig.contains("vm.storage.class=gov.nasa.jpf.vm.JenkinsStateSet"));
        assertTrue(bootstrapScript.contains("jpf-core"));
        assertTrue(runScript.contains("RunJPF.jar"));
        assertTrue(runScript.contains("jpf.jar"));
        assertTrue(runScript.contains("jpf-classes.jar"));
        assertTrue(runScript.contains("jpf-annotations.jar"));
        assertTrue(runScript.contains("--model"));
        assertTrue(runScript.contains("--docker"));
        assertTrue(runScript.contains("\"docker\", \"build\""));
        assertTrue(runScript.contains("run_model_in_docker"));
        assertTrue(runScript.contains("docker_command"));
        assertTrue(runScript.contains("compile_minimal_harnesses(repo_root)"));
        assertTrue(runScript.contains("--release"));
        assertTrue(runScript.contains("sed -i 's/\\\\r$//' gradlew"));
    }

    @Test
    void minimalJpfModelsRunUnderJpfWhenRuntimeJarsAreAvailable() throws Exception {
        Path buildDir = Path.of("verification", "jpf", ".jpf-core", "build");
        Path libDir = Path.of("verification", "jpf", ".jpf-core", "lib");
        assumeTrue(Files.isDirectory(buildDir) && Files.isDirectory(libDir),
                "Build jpf-core first so verification/jpf/.jpf-core/build and lib exist");
        assumeTrue(Files.isDirectory(buildDir.resolve("main"))
                        && Files.isDirectory(buildDir.resolve("peers"))
                        && Files.isDirectory(buildDir.resolve("annotations"))
                        && Files.isDirectory(buildDir.resolve("tests"))
                        && Files.isRegularFile(libDir.resolve("bcel.jar"))
                        && Files.isRegularFile(libDir.resolve("junit-4.10.jar")),
                "Build jpf-core first so the JPF runtime classpath exists under verification/jpf/.jpf-core");

        compileMinimalHarnesses();

        runJpf("threaded-minimal.jpf");
        runJpf("taskbased-minimal.jpf");
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

    private static void runJpf(String configFile) throws IOException, InterruptedException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        Path configPath = Path.of("verification", "jpf", configFile);
        Path jpfBuild = Path.of("verification", "jpf", ".jpf-core", "build");
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable,
                "-ea",
                "-jar",
                jpfBuild.resolve("RunJPF.jar").toString(),
                configPath.toString());
        builder.directory(Path.of(".").toFile());
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
