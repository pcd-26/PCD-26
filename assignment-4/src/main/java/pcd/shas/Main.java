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
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.keypad.KeypadActor;
import pcd.shas.sensor.SensorActor;
import pcd.shas.siren.SirenActor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.CompletionStage;

/**
 * Main entry point for the clustered Smart Home Alarm System (SHAS).
 *
 * <p>Can run either in automatic simulation mode (default, starts a local cluster
 * and simulates failure and recovery) or in manual role-specific node mode
 * (control-unit, keypad, sensor).</p>
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final String SYSTEM_NAME = "shas-cluster";

    private Main() {
        // Utility class.
    }

    public static void main(String[] args) {
        if (args.length == 0 || "demo".equalsIgnoreCase(args[0])) {
            runAutomaticDemo();
        } else {
            runManualRole(args);
        }
    }

    private static void runAutomaticDemo() {
        LOGGER.info("Starting automatic clustered SHAS demo...");

        Config baseConfig = ConfigFactory.load();

        // 1. Start Control Unit node on port 2551
        LOGGER.info("Starting Control Unit Node on port 2551...");
        Config config1 = ConfigFactory.parseString(
                "pekko.remote.artery.canonical.port = 2551"
        ).withFallback(baseConfig);
        ActorSystem<ControlUnitActor.Command> cuSystem = ActorSystem.create(
                createControlUnitNodeBehavior(),
                SYSTEM_NAME,
                config1
        );

        // 2. Start Keypad node on port 2552
        LOGGER.info("Starting Keypad Node on port 2552...");
        Config config2 = ConfigFactory.parseString(
                "pekko.remote.artery.canonical.port = 2552"
        ).withFallback(baseConfig);
        ActorSystem<KeypadActor.Command> keypadSystem = ActorSystem.create(
                KeypadActor.create(),
                SYSTEM_NAME,
                config2
        );

        // 3. Start Sensors node on port 2553
        LOGGER.info("Starting Sensors Node on port 2553...");
        Config config3 = ConfigFactory.parseString(
                "pekko.remote.artery.canonical.port = 2553"
        ).withFallback(baseConfig);
        ActorSystem<SensorActor.Command> sensorsSystem = ActorSystem.create(
                createSensorsNodeBehavior(),
                SYSTEM_NAME,
                config3
        );

        try {
            // Wait for cluster formation
            LOGGER.info("Waiting for cluster to form...");
            Thread.sleep(3000);

            // Fetch Control Unit actor reference from system1
            // We can query its state
            LOGGER.info("[DEMO STEP 1] Querying initial state of Control Unit (should be RECOVERY)...");
            AlarmState state = queryControlUnitState(cuSystem);
            LOGGER.info("[DEMO RESULT] Control Unit State: {}", state);

            // Submit incorrect PIN
            LOGGER.info("[DEMO STEP 2] Submitting incorrect PIN '9999' from Keypad on Node 2...");
            keypadSystem.tell(new KeypadActor.SubmitPin("9999"));
            Thread.sleep(500);
            LOGGER.info("[DEMO RESULT] Control Unit State: {}", queryControlUnitState(cuSystem));

            // Trigger Sensor on Node 3 while in RECOVERY
            LOGGER.info("[DEMO STEP 3] Activating Front Door Sensor on Node 3 (should be ignored in RECOVERY)...");
            sensorsSystem.tell(new SensorActor.Activate());
            Thread.sleep(500);

            // Submit correct PIN "1234"
            LOGGER.info("[DEMO STEP 4] Submitting correct PIN '1234' from Keypad...");
            keypadSystem.tell(new KeypadActor.SubmitPin("1234"));
            Thread.sleep(1000);
            state = queryControlUnitState(cuSystem);
            LOGGER.info("[DEMO RESULT] Control Unit State: {}", state);

            // Arm system
            LOGGER.info("[DEMO STEP 5] Arming the system (transition to EXIT_DELAY)...");
            cuSystem.tell(new ControlUnitActor.ArmAll());
            keypadSystem.tell(new KeypadActor.SubmitPin("1234"));
            Thread.sleep(500);
            LOGGER.info("[DEMO RESULT] Control Unit State: {}", queryControlUnitState(cuSystem));

            // Wait for EXIT_DELAY (configured as 5 seconds in application.conf)
            LOGGER.info("Waiting for EXIT_DELAY (5s)...");
            Thread.sleep(5500);
            LOGGER.info("[DEMO RESULT] Control Unit State: {}", queryControlUnitState(cuSystem));

            // CRASH / RESTART OF CONTROL UNIT
            LOGGER.info("[DEMO STEP 6] Simulating Control Unit crash: terminating Node 1...");
            cuSystem.terminate();
            cuSystem.getWhenTerminated().toCompletableFuture().join();
            LOGGER.info("Node 1 terminated.");
            Thread.sleep(2000);

            // Recreate Control Unit node on port 2551
            LOGGER.info("[DEMO STEP 7] Restarting Control Unit Node on port 2551...");
            ActorSystem<ControlUnitActor.Command> restartedCuSystem = ActorSystem.create(
                    createControlUnitNodeBehavior(),
                    SYSTEM_NAME,
                    config1
            );
            Thread.sleep(3000); // Wait for reconnection

            LOGGER.info("[DEMO STEP 8] Querying restarted Control Unit state (should enter RECOVERY)...");
            AlarmState restartedState = queryControlUnitState(restartedCuSystem);
            LOGGER.info("[DEMO RESULT] Restarted Control Unit State: {}", restartedState);

            // Trigger Sensor again: should be ignored because of RECOVERY
            LOGGER.info("[DEMO STEP 9] Activating sensor (should be ignored)...");
            sensorsSystem.tell(new SensorActor.Activate());
            Thread.sleep(500);

            // Submit correct PIN on restarted Control Unit to disarm it
            LOGGER.info("[DEMO STEP 10] Submitting correct PIN '1234' on Keypad (Node 2) to recovery-disarm...");
            keypadSystem.tell(new KeypadActor.SubmitPin("1234"));
            Thread.sleep(1000);
            LOGGER.info("[DEMO RESULT] Restarted Control Unit State: {}", queryControlUnitState(restartedCuSystem));

            LOGGER.info("Automatic demo completed successfully!");
        } catch (Exception e) {
            LOGGER.error("Demo failed with exception", e);
        } finally {
            LOGGER.info("Shutting down cluster...");
            keypadSystem.terminate();
            sensorsSystem.terminate();
            cuSystem.terminate();
        }
    }

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

    private static void runManualRole(String[] args) {
        String role = args[0].toLowerCase();
        int port = 0;
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                LOGGER.error("Invalid port: {}", args[1]);
                return;
            }
        }

        Config baseConfig = ConfigFactory.load();
        Config config = ConfigFactory.parseString("pekko.remote.artery.canonical.port = " + port)
                .withFallback(baseConfig);

        LOGGER.info("Starting role '{}' on port {}...", role, port);

        switch (role) {
            case "control-unit" -> {
                ActorSystem<ControlUnitActor.Command> system = ActorSystem.create(
                        createControlUnitNodeBehavior(),
                        SYSTEM_NAME,
                        config
                );
                LOGGER.info("Control Unit Node running. Press Enter to shutdown.");
                waitEnter();
                system.terminate();
            }
            case "keypad" -> {
                ActorSystem<KeypadActor.Command> system = ActorSystem.create(
                        KeypadActor.create(),
                        SYSTEM_NAME,
                        config
                );
                LOGGER.info("Keypad Node running. Type digits to type PIN, '#' to submit, '*' to clear.");
                startKeypadConsoleInputReader(system);
            }
            case "sensor" -> {
                if (args.length < 5) {
                    LOGGER.error("Sensor usage: Main sensor <port> <sensorId> <sensorType: MOTION/DOOR_WINDOW> <zone: PERIMETER/GROUND_FLOOR/LIVING_AREA/SLEEPING_AREA>");
                    return;
                }
                String id = args[2];
                SensorType type = SensorType.valueOf(args[3].toUpperCase());
                Zone zone = Zone.valueOf(args[4].toUpperCase());

                ActorSystem<SensorActor.Command> system = ActorSystem.create(
                        SensorActor.create(id, type, zone),
                        SYSTEM_NAME,
                        config
                );
                LOGGER.info("Sensor Node running (ID={}, Type={}, Zone={}). Press Enter to trigger.", id, type, zone);
                startSensorConsoleInputReader(system);
            }
            default -> LOGGER.error("Unknown role: {}", role);
        }
    }

    private static Behavior<ControlUnitActor.Command> createControlUnitNodeBehavior() {
        return Behaviors.setup(context -> {
            // Spawn Siren
            context.spawn(SirenActor.create(), "siren");
            // Spawn Control Unit
            ActorRef<ControlUnitActor.Command> cu = context.spawn(
                    ControlUnitActor.create("1234"),
                    "control-unit"
            );
            // Forward commands sent to root system to the control unit
            return Behaviors.receive(ControlUnitActor.Command.class)
                    .onMessage(ControlUnitActor.Command.class, msg -> {
                        cu.tell(msg);
                        return Behaviors.same();
                    })
                    .build();
        });
    }

    private static Behavior<SensorActor.Command> createSensorsNodeBehavior() {
        return Behaviors.setup(context -> {
            ActorRef<SensorActor.Command> door = context.spawn(
                    SensorActor.create("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER),
                    "front-door-sensor"
            );
            ActorRef<SensorActor.Command> motion = context.spawn(
                    SensorActor.create("living_room_motion", SensorType.MOTION, Zone.LIVING_AREA),
                    "living-room-sensor"
            );

            return Behaviors.receive(SensorActor.Command.class)
                    .onMessage(SensorActor.Activate.class, msg -> {
                        door.tell(msg);
                        motion.tell(msg);
                        return Behaviors.same();
                    })
                    .build();
        });
    }

    private static void waitEnter() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            reader.readLine();
        } catch (Exception e) {
            // ignore
        }
    }

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
                    for (char c : line.toCharArray()) {
                        system.tell(new KeypadActor.PressKey(c));
                    }
                    system.tell(new KeypadActor.PressKey('#'));
                    LOGGER.info("Typed PIN sequence processed.");
                }
            } catch (Exception e) {
                // ignore
            }
        }).start();
    }

    private static void startSensorConsoleInputReader(ActorSystem<SensorActor.Command> system) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                while (reader.readLine() != null) {
                    system.tell(new SensorActor.Activate());
                    LOGGER.info("Simulation: Activated sensor.");
                }
            } catch (Exception e) {
                // ignore
            }
        }).start();
    }
}
