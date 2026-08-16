package pcd.shas;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Command-line entry point for the clustered smart home alarm system.
 *
 * <p>The application supports one JVM per role for the distributed setup and a
 * local demo mode that launches three roles on {@code 127.0.0.1}.</p>
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final String SYSTEM_NAME = "shas-cluster";
    private static final String LOCAL_HOST = "127.0.0.1";
    private static final int CONTROL_UNIT_PORT = 2551;
    private static final int KEYPAD_PORT = 2552;
    private static final int SENSOR_PORT = 2553;
    private static final List<String> LOCAL_SEED_NODES = List.of(
        NodeStartup.toSeedNodeUri(SYSTEM_NAME, LOCAL_HOST, CONTROL_UNIT_PORT),
        NodeStartup.toSeedNodeUri(SYSTEM_NAME, LOCAL_HOST, KEYPAD_PORT),
        NodeStartup.toSeedNodeUri(SYSTEM_NAME, LOCAL_HOST, SENSOR_PORT)
    );

    private Main() {}   // Utility class

    /**
     * Starts the requested node role or the local demo.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        if (args.length == 0 || "demo".equalsIgnoreCase(args[0])) {
            runLocalDemo();
            return;
        }

        try {
            NodeArguments launchArguments = NodeStartup.parseNodeArguments(args);
            runNode(launchArguments);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid startup arguments: {}", e.getMessage());
        }
    }

    /**
     * Executes the local demo mode, spinning up ControlUnit, Keypad, and Sensor
     * nodes in the same JVM to demonstrate state transitions and cluster recovery.
     */
    private static void runLocalDemo() {
        LOGGER.info("Starting local three-node SHAS demo...");

        Config baseConfig = ConfigFactory.load();
        Config controlUnitConfig = NodeStartup.buildClusterConfig(
            SYSTEM_NAME,
            LOCAL_HOST,
            CONTROL_UNIT_PORT,
            LOCAL_SEED_NODES
        ).withFallback(baseConfig);
        Config keypadConfig = NodeStartup.buildClusterConfig(
            SYSTEM_NAME,
            LOCAL_HOST,
            KEYPAD_PORT,
            LOCAL_SEED_NODES
        ).withFallback(baseConfig);
        Config sensorConfig = NodeStartup.buildClusterConfig(
            SYSTEM_NAME,
            LOCAL_HOST,
            SENSOR_PORT,
            LOCAL_SEED_NODES
        ).withFallback(baseConfig);
        AlarmConfiguration alarmConfiguration = AlarmConfiguration.from(controlUnitConfig);

        ActorSystem<ControlUnitActor.Command> controlUnitSystem = ActorSystem.create(
            createControlUnitNodeBehavior(alarmConfiguration),
            SYSTEM_NAME,
            controlUnitConfig
        );
        ActorSystem<KeypadActor.Command> keypadSystem = ActorSystem.create(
            KeypadActor.create(),
            SYSTEM_NAME,
            keypadConfig
        );
        ActorSystem<SensorActor.Command> sensorSystem = ActorSystem.create(
            createSensorsNodeBehavior(),
            SYSTEM_NAME,
            sensorConfig
        );
        ActorSystem<ControlUnitActor.Command> restartedControlUnitSystem = null;

        try {
            LOGGER.info("Waiting for cluster formation...");
            Thread.sleep(3000);

            LOGGER.info("[DEMO] Initial state: {}", queryControlUnitState(controlUnitSystem));
            keypadSystem.tell(new KeypadActor.SubmitPin("9999"));
            Thread.sleep(500);
            LOGGER.info("[DEMO] After wrong PIN: {}", queryControlUnitState(controlUnitSystem));

            sensorSystem.tell(new SensorActor.Activate());
            Thread.sleep(500);

            keypadSystem.tell(new KeypadActor.SubmitPin("1234"));
            Thread.sleep(1000);
            LOGGER.info("[DEMO] After correct PIN: {}", queryControlUnitState(controlUnitSystem));

            keypadSystem.tell(new KeypadActor.RequestFullArming("1234"));
            Thread.sleep(500);
            LOGGER.info("[DEMO] After arming request: {}", queryControlUnitState(controlUnitSystem));

            Thread.sleep(5500);
            LOGGER.info("[DEMO] After exit delay: {}", queryControlUnitState(controlUnitSystem));

            controlUnitSystem.terminate();
            controlUnitSystem.getWhenTerminated().toCompletableFuture().join();

            restartedControlUnitSystem = ActorSystem.create(
                createControlUnitNodeBehavior(alarmConfiguration),
                SYSTEM_NAME,
                controlUnitConfig
            );
            Thread.sleep(3000);

            LOGGER.info("[DEMO] Restarted state: {}", queryControlUnitState(restartedControlUnitSystem));
            sensorSystem.tell(new SensorActor.Activate());
            Thread.sleep(500);

            keypadSystem.tell(new KeypadActor.SubmitPin("1234"));
            Thread.sleep(1000);
            LOGGER.info("[DEMO] Final restarted state: {}", queryControlUnitState(restartedControlUnitSystem));
        } catch (Exception e) {
            LOGGER.error("Demo failed with exception", e);
        } finally {
            LOGGER.info("Shutting down demo systems...");
            keypadSystem.terminate();
            sensorSystem.terminate();
            controlUnitSystem.terminate();
            if (restartedControlUnitSystem != null) {
                restartedControlUnitSystem.terminate();
            }
        }
    }

    /**
     * Launches a single node process according to parsed command-line arguments.
     *
     * @param launchArguments the parsed node role and network parameters
     */
    private static void runNode(NodeArguments launchArguments) {
        Config nodeConfig = NodeStartup.buildClusterConfig(
            SYSTEM_NAME,
            launchArguments.host(),
            launchArguments.port(),
            launchArguments.seedNodes()
        );
        AlarmConfiguration alarmConfiguration = AlarmConfiguration.from(nodeConfig);

        switch (launchArguments.role()) {
            case CONTROL_UNIT -> runControlUnitNode(nodeConfig, alarmConfiguration);
            case KEYPAD -> runKeypadNode(nodeConfig);
            case SENSOR -> runSensorNode(nodeConfig, launchArguments);
        }
    }

    /**
     * Initializes and runs the Control Unit node and its local Siren actor.
     *
     * @param config application configuration for Pekko Cluster Artery binding
     * @param alarmConfiguration loaded alarm timeouts and PIN configuration
     */
    private static void runControlUnitNode(Config config, AlarmConfiguration alarmConfiguration) {
        ActorSystem<ControlUnitActor.Command> system = ActorSystem.create(
            createControlUnitNodeBehavior(alarmConfiguration),
            SYSTEM_NAME,
            config
        );
        LOGGER.info("Control unit node running on {}:{}", config.getString("pekko.remote.artery.canonical.hostname"), config.getInt("pekko.remote.artery.canonical.port"));
        waitEnter();
        system.terminate();
    }

    /**
     * Initializes and runs a standalone Keypad node with console input parsing.
     *
     * @param config application configuration for Pekko Cluster Artery binding
     */
    private static void runKeypadNode(Config config) {
        ActorSystem<KeypadActor.Command> system = ActorSystem.create(
            KeypadActor.create(),
            SYSTEM_NAME,
            config
        );
        LOGGER.info("Keypad node running on {}:{}", config.getString("pekko.remote.artery.canonical.hostname"), config.getInt("pekko.remote.artery.canonical.port"));
        LOGGER.info("Use 'arm full <PIN>', 'arm partial <PIN> ZONE...', or 'pin <PIN>'. Type 'exit' to stop the node.");
        startKeypadConsoleInputReader(system);
    }

    /**
     * Initializes and runs a standalone Sensor node.
     *
     * @param config application configuration for Pekko Cluster Artery binding
     * @param launchArguments metadata specifying sensor ID, type, and zone
     */
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
        LOGGER.info("Press Enter to trigger the sensor. Type 'exit' to stop the node.");
        startSensorConsoleInputReader(system);
    }

    /**
     * Asynchronously queries the control unit's current logical alarm state.
     *
     * @param system the ControlUnit ActorSystem instance
     * @return current {@link AlarmState}, or {@code null} if query fails
     */
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

    /**
     * Creates the root behavior for a Control Unit node, spawning the Siren actor and ControlUnit actor.
     *
     * @param alarmConfiguration configured PIN and timing settings
     * @return typed behavior forwarding commands to the ControlUnit actor
     */
    private static Behavior<ControlUnitActor.Command> createControlUnitNodeBehavior(AlarmConfiguration alarmConfiguration) {
        return Behaviors.setup(context -> {
            context.spawn(SirenActor.create(), "siren");
            ActorRef<ControlUnitActor.Command> controlUnit = context.spawn(
                ControlUnitActor.create(
                    alarmConfiguration.correctPin(),
                    alarmConfiguration.exitDelay(),
                    alarmConfiguration.entryDelay()
                ),
                "control-unit"
            );
            return Behaviors.receive(ControlUnitActor.Command.class)
                .onMessage(ControlUnitActor.Command.class, message -> {
                    controlUnit.tell(message);
                    return Behaviors.same();
                })
                .build();
        });
    }

    /**
     * Creates a demo behavior managing multiple simulated sensors (door and motion).
     *
     * @return typed behavior routing activation messages to child sensors
     */
    private static Behavior<SensorActor.Command> createSensorsNodeBehavior() {
        return Behaviors.setup(context -> {
            ActorRef<SensorActor.Command> doorSensor = context.spawn(
                SensorActor.create("front_door", pcd.shas.common.SensorType.DOOR_WINDOW, pcd.shas.common.Zone.PERIMETER),
                "front-door-sensor"
            );
            ActorRef<SensorActor.Command> motionSensor = context.spawn(
                SensorActor.create("living_room_motion", pcd.shas.common.SensorType.MOTION, pcd.shas.common.Zone.LIVING_AREA),
                "living-room-sensor"
            );

            return Behaviors.receive(SensorActor.Command.class)
                .onMessage(SensorActor.Activate.class, message -> {
                    doorSensor.tell(message);
                    motionSensor.tell(message);
                    return Behaviors.same();
                })
                .build();
        });
    }

    /**
     * Blocks the main thread until the user presses Enter in standard input.
     */
    private static void waitEnter() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            reader.readLine();
        } catch (Exception ignored) {
            // Ignore shutdown input failures.
        }
    }

    /**
     * Starts a daemon thread reading keypad entries from console input.
     *
     * @param system target Keypad ActorSystem
     */
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
        }).start();
    }

    /**
     * Parses a whitespace/comma separated zone list for partial arming.
     *
     * @param input raw zone list
     * @return immutable set of zones
     */
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

    /**
     * Starts a daemon thread reading sensor activation triggers from console input.
     *
     * @param system target Sensor ActorSystem
     */
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
        }).start();
    }
}
