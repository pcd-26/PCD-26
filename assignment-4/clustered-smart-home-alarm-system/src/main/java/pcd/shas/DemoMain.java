package pcd.shas;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DemoMain {

    private static final String HOST = "127.0.0.1";
    private static final int CONTROL_UNIT_PORT = 2551;
    private static final int KEYPAD_PORT = 2552;
    private static final int SENSOR_PORT = 2553;
    private static final String SEED_NODES = HOST + ":" + CONTROL_UNIT_PORT
        + "," + HOST + ":" + KEYPAD_PORT
        + "," + HOST + ":" + SENSOR_PORT;
    private static final Duration CLUSTER_STARTUP = Duration.ofSeconds(10);
    private static final Duration STEP_GAP = Duration.ofSeconds(1);
    private static final Duration EXIT_DELAY = Duration.ofSeconds(6);

    private DemoMain() {}   // Utility class

    // Starts separate JVM processes and drives a distributed alarm scenario.
    public static void main(String[] args) {
        List<NodeProcess> nodes = new ArrayList<>();
        try {
            Path moduleDirectory = moduleDirectory();
            System.out.println("[DEMO] Starting a distributed CSHAS demo with three separate node processes.");
            System.out.println("[DEMO] Seed nodes: " + SEED_NODES);

            nodes.add(startNode(moduleDirectory, "CONTROL", List.of(
                "control-unit",
                "--host", HOST,
                "--port", String.valueOf(CONTROL_UNIT_PORT),
                "--seed-nodes", SEED_NODES
            )));
            nodes.add(startNode(moduleDirectory, "KEYPAD", List.of(
                "keypad",
                "--host", HOST,
                "--port", String.valueOf(KEYPAD_PORT),
                "--seed-nodes", SEED_NODES
            )));
            nodes.add(startNode(moduleDirectory, "SENSOR", List.of(
                "sensor",
                "--host", HOST,
                "--port", String.valueOf(SENSOR_PORT),
                "--sensor-id", "front_door",
                "--sensor-type", "DOOR_WINDOW",
                "--zone", "PERIMETER",
                "--seed-nodes", SEED_NODES
            )));

            runScenario(nodes.get(0), nodes.get(1), nodes.get(2));
        } catch (Exception e) {
            System.err.println("[DEMO] Demo failed: " + e.getMessage());
            e.printStackTrace(System.err);
        } finally {
            stopNodes(nodes);
        }
    }

    // Drives the user-visible scenario through node stdin streams.
    private static void runScenario(NodeProcess controlUnit, NodeProcess keypad, NodeProcess sensor) {
        pause(CLUSTER_STARTUP);
        System.out.println();
        System.out.println("[DEMO] Cluster is expected to be formed now.");

        step("The control unit starts in STARTUP_RECOVERY, so a sensor event is logged but ignored.");
        sensor.send("");
        pause(STEP_GAP);
        controlUnit.send("status");
        pause(STEP_GAP);

        step("A wrong PIN does not leave recovery mode.");
        keypad.send("pin 9999");
        pause(STEP_GAP);
        controlUnit.send("status");
        pause(STEP_GAP);

        step("The correct PIN moves the recovered control unit to DISARMED.");
        keypad.send("pin 1234");
        pause(STEP_GAP);
        controlUnit.send("status");
        pause(STEP_GAP);

        step("Full arming starts EXIT_DELAY; sensors are still ignored while people leave.");
        keypad.send("arm full 1234");
        pause(STEP_GAP);
        sensor.send("");
        pause(STEP_GAP);
        controlUnit.send("status");

        step("After EXIT_DELAY expires, the same distributed sensor starts ENTRY_DELAY.");
        pause(EXIT_DELAY);
        sensor.send("");
        pause(STEP_GAP);
        controlUnit.send("status");

        step("The correct PIN during ENTRY_DELAY disarms the alarm before the siren starts.");
        keypad.send("pin 1234");
        pause(STEP_GAP);
        controlUnit.send("status");

        step("Demo complete. Stopping all node processes.");
    }

    // Starts one child process running Main with the selected role arguments.
    private static NodeProcess startNode(Path moduleDirectory, String label, List<String> roleArguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.addAll(mavenCommand());
        Path settingsFile = moduleDirectory.resolve("../../.mvn/settings.xml").normalize();
        if (Files.isRegularFile(settingsFile)) {
            command.add("-s");
            command.add(settingsFile.toString());
        }
        command.add("-f");
        command.add(moduleDirectory.resolve("pom.xml").toString());
        command.add("--batch-mode");
        command.add("--no-transfer-progress");
        command.add("compile");
        command.add("exec:java");
        command.add("-Dexec.mainClass=pcd.shas.Main");
        command.add("-DskipTests");
        command.add("-Dexec.args=" + String.join(" ", roleArguments));

        Process process = new ProcessBuilder(command)
            .directory(moduleDirectory.toFile())
            .redirectErrorStream(true)
            .start();
        NodeProcess nodeProcess = new NodeProcess(label, process);
        nodeProcess.startOutputReader();
        System.out.println("[DEMO] Started " + label + " process with args: " + String.join(" ", roleArguments));
        return nodeProcess;
    }

    // Stops nodes through their interactive command before forcing cleanup if needed.
    private static void stopNodes(List<NodeProcess> nodes) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).send("exit");
        }
        pause(Duration.ofSeconds(2));
        for (int i = nodes.size() - 1; i >= 0; i--) {
            nodes.get(i).destroy();
        }
    }

    // Prints a readable scenario step.
    private static void step(String message) {
        System.out.println();
        System.out.println("[DEMO] " + message);
    }

    // Resolves the module directory from the compiled classes location.
    private static Path moduleDirectory() throws URISyntaxException {
        Path classesDirectory = Path.of(DemoMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return classesDirectory.getParent().getParent();
    }

    // Chooses the Maven command for the current platform.
    private static List<String> mavenCommand() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return List.of("cmd", "/c", "mvn");
        }
        return List.of("mvn");
    }

    // Sleeps between demo steps while preserving interruption.
    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo interrupted", exception);
        }
    }

    private static final class NodeProcess {

        private final String label;
        private final Process process;
        private final BufferedWriter input;

        private NodeProcess(String label, Process process) {
            this.label = label;
            this.process = process;
            this.input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        }

        // Streams child-process output with a stable node prefix.
        private void startOutputReader() {
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                )) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[" + label + "] " + line);
                    }
                } catch (IOException ignored) {
                    // The process may close its output while the demo is shutting down.
                }
            }, "demo-" + label.toLowerCase(Locale.ROOT) + "-output");
            outputThread.setDaemon(true);
            outputThread.start();
        }

        // Sends one interactive command to the node process.
        private void send(String command) {
            try {
                input.write(command);
                input.newLine();
                input.flush();
            } catch (IOException e) {
                System.err.println("[DEMO] Cannot send command to " + label + ": " + e.getMessage());
            }
        }

        // Ensures the child process is not left running.
        private void destroy() {
            if (process.isAlive()) {
                process.destroy();
            }
        }
    }
}
