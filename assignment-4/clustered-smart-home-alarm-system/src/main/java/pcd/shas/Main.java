package pcd.shas;

import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pcd.shas.common.AlarmState;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.keypad.KeypadActor;
import pcd.shas.runtime.NodeStartup;
import pcd.shas.runtime.NodeStartup.NodeArguments;
import pcd.shas.sensor.SensorActor;
import pcd.shas.siren.SirenActor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;

public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final String SYSTEM_NAME = "shas-cluster";

    private Main() {}   // Utility class

    // Starts one clustered node with its interactive CLI.
    public static void main(String[] args) {
        if (args.length == 0 || "demo".equalsIgnoreCase(args[0])) {
            printUsage();
            return;
        }

        try {
            NodeArguments launchArguments = NodeStartup.parseNodeArguments(args);
            runNode(launchArguments);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid startup arguments: {}", e.getMessage());
            printUsage();
        }
    }

    // Launches one clustered node from parsed command-line arguments.
    private static void runNode(NodeArguments launchArguments) {
        Config nodeConfig = NodeStartup.buildClusterConfig(
            SYSTEM_NAME,
            launchArguments.host(),
            launchArguments.port(),
            launchArguments.seedNodes(),
            launchArguments.role()
        );
        AlarmConfiguration alarmConfiguration = AlarmConfiguration.from(nodeConfig);

        switch (launchArguments.role()) {
            case CONTROL_UNIT -> runControlUnitNode(nodeConfig, alarmConfiguration);
            case KEYPAD -> runKeypadNode(nodeConfig);
            case SENSOR -> runSensorNode(nodeConfig, launchArguments);
        }
    }

    // Runs a control-unit node with its local siren actor.
    private static void runControlUnitNode(Config config, AlarmConfiguration alarmConfiguration) {
        ActorSystem<ControlUnitActor.Command> system = ActorSystem.create(
            createControlUnitNodeBehavior(alarmConfiguration),
            SYSTEM_NAME,
            config
        );
        LOGGER.info(
            "Control unit node running on {}:{}",
            config.getString("pekko.remote.artery.canonical.hostname"),
            config.getInt("pekko.remote.artery.canonical.port")
        );
        LOGGER.info("Commands: status, help, exit.");
        startControlUnitConsoleInputReader(system);
        waitForTermination(system);
    }

    // Runs a keypad node and reads commands from the console.
    private static void runKeypadNode(Config config) {
        ActorSystem<KeypadActor.Command> system = ActorSystem.create(
            KeypadActor.create(),
            SYSTEM_NAME,
            config
        );
        LOGGER.info(
            "Keypad node running on {}:{}",
            config.getString("pekko.remote.artery.canonical.hostname"),
            config.getInt("pekko.remote.artery.canonical.port")
        );
        LOGGER.info("Commands: arm full <PIN>, arm partial <PIN> ZONE..., pin <PIN>, raw digits, exit.");
        startKeypadConsoleInputReader(system);
        waitForTermination(system);
    }

    // Runs a sensor node using the sensor metadata from CLI arguments.
    private static void runSensorNode(Config config, NodeArguments launchArguments) {
        ActorSystem<SensorActor.Command> system = ActorSystem.create(
            SensorActor.create(
                launchArguments.sensorId(),
                launchArguments.sensorType(),
                launchArguments.zone()
            ),
            SYSTEM_NAME,
            config
        );
        LOGGER.info(
            "Sensor node running on {}:{} (id={}, type={}, zone={})",
            config.getString("pekko.remote.artery.canonical.hostname"),
            config.getInt("pekko.remote.artery.canonical.port"),
            launchArguments.sensorId(),
            launchArguments.sensorType(),
            launchArguments.zone()
        );
        LOGGER.info("Press ENTER to trigger the sensor. Type 'exit' to stop the node.");
        startSensorConsoleInputReader(system);
        waitForTermination(system);
    }

    // Queries the control unit state through ask.
    private static AlarmState queryControlUnitState(ActorSystem<ControlUnitActor.Command> system) {
        try {
            CompletionStage<ControlUnitActor.StateSnapshot> stage = AskPattern.ask(
                system,
                ControlUnitActor.QueryState::new,
                Duration.ofSeconds(2),
                system.scheduler()
            );
            return stage.toCompletableFuture().get().state();
        } catch (Exception e) {
            LOGGER.error("Failed to query state", e);
            return null;
        }
    }

    // Creates the root behavior for a control-unit node.
    private static Behavior<ControlUnitActor.Command> createControlUnitNodeBehavior(AlarmConfiguration alarmConfiguration) {
        return Behaviors.setup(context -> {
            // The siren stays local to the control-unit node and is discovered by service key.
            context.spawn(SirenActor.create(), "siren");
            ActorRef<ControlUnitActor.Command> controlUnit = ControlUnitActor.initSingleton(
                context.getSystem(),
                alarmConfiguration.correctPin(),
                alarmConfiguration.exitDelay(),
                alarmConfiguration.entryDelay()
            );
            // The ActorSystem itself forwards external commands to the child control unit.
            return Behaviors.receive(ControlUnitActor.Command.class)
                .onMessage(ControlUnitActor.Command.class, message -> {
                    controlUnit.tell(message);
                    return Behaviors.same();
                })
                .build();
        });
    }

    // Starts console input for a control-unit node.
    private static void startControlUnitConsoleInputReader(ActorSystem<ControlUnitActor.Command> system) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String command = line.trim();
                    if (command.equalsIgnoreCase("exit")) {
                        system.terminate();
                        break;
                    }
                    if (command.equalsIgnoreCase("status")) {
                        LOGGER.info("Alarm state: {}", queryControlUnitState(system));
                        continue;
                    }
                    if (command.equalsIgnoreCase("help")) {
                        LOGGER.info("Commands: status, help, exit.");
                        continue;
                    }
                    LOGGER.warn("Unknown control-unit command: {}", command);
                }
            } catch (Exception ignored) {
                // Ignore console shutdown failures.
            }
        }, "control-unit-console").start();
    }

    // Starts console input for a keypad node.
    private static void startKeypadConsoleInputReader(ActorSystem<KeypadActor.Command> system) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.equalsIgnoreCase("exit")) {
                        system.terminate();
                        break;
                    }
                    // Keep the same commands used by the non-clustered CLI.
                    if (line.startsWith("arm full ")) {
                        system.tell(new KeypadActor.RequestFullArming(line.substring("arm full ".length()).trim()));
                        continue;
                    }
                    if (line.startsWith("arm partial ")) {
                        try {
                            String[] parts = line.substring("arm partial ".length()).trim().split("\\s+", 2);
                            if (parts.length < 2) {
                                throw new IllegalArgumentException("PIN and at least one zone are required");
                            }
                            system.tell(new KeypadActor.RequestPartialArming(parts[0], parseZones(parts[1])));
                        } catch (IllegalArgumentException exception) {
                            LOGGER.warn("Invalid partial arming request. Use: arm partial <PIN> PERIMETER GROUND_FLOOR");
                        }
                        continue;
                    }
                    if (line.startsWith("pin ")) {
                        system.tell(new KeypadActor.SubmitPin(line.substring("pin ".length()).trim()));
                        continue;
                    }
                    for (char c : line.toCharArray()) {
                        system.tell(new KeypadActor.PressKey(c));
                    }
                    system.tell(new KeypadActor.PressKey('#'));
                }
            } catch (Exception ignored) {
                // Ignore console shutdown failures.
            }
        }, "keypad-console").start();
    }

    // Parses the zone list used by partial arming commands.
    private static Set<pcd.shas.common.Zone> parseZones(String input) {
        String normalizedInput = input.toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace(',', ' ')
            .trim();

        if (normalizedInput.isEmpty()) {
            throw new IllegalArgumentException("At least one zone is required");
        }

        return Set.of(normalizedInput.split("\\s+"))
            .stream()
            .map(pcd.shas.common.Zone::valueOf)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // Starts console input for a sensor node.
    private static void startSensorConsoleInputReader(ActorSystem<SensorActor.Command> system) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().equalsIgnoreCase("exit")) {
                        system.terminate();
                        break;
                    }
                    system.tell(new SensorActor.Activate());
                    LOGGER.info("Sensor activation requested from console.");
                }
            } catch (Exception ignored) {
                // Ignore console shutdown failures.
            }
        }, "sensor-console").start();
    }

    // Waits until the actor system completes its coordinated shutdown.
    private static void waitForTermination(ActorSystem<?> system) {
        system.getWhenTerminated().toCompletableFuture().join();
    }

    // Prints the node-oriented command-line contract.
    private static void printUsage() {
        System.out.println("""
            Clustered Smart Home Alarm System

            Start one interactive clustered node:
              control-unit --host 127.0.0.1 --port 2551 --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
              keypad       --host 127.0.0.1 --port 2552 --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
              sensor       --host 127.0.0.1 --port 2553 --sensor-id front_door --sensor-type DOOR_WINDOW --zone PERIMETER --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553

            Run the distributed process demo through pcd.shas.DemoMain or:
              run-cshas demo
            """);
    }
}
