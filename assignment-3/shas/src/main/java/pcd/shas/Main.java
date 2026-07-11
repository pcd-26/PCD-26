package pcd.shas;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.keypad.KeypadActor;
import pcd.shas.sensor.SensorActor;
import pcd.shas.siren.AlertDevice;
import pcd.shas.siren.SirenActor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;

/**
 * The main application entry point that bootstraps the Apache Pekko actor system
 * and provides an interactive command line interface (CLI) to simulate user actions
 * and sensor triggers.
 */
public class Main {

    // ANSI Escape Codes for CLI Styling
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    private static final String DEFAULT_PIN = "1234";
    private static final Duration EXIT_DELAY = Duration.ofSeconds(15);
    private static final Duration ENTRY_DELAY = Duration.ofSeconds(10);

    /**
     * Bootstraps the application.
     *
     * @param args command line arguments (ignored)
     */
    public static void main(String[] args) {
        System.out.println(CYAN + BOLD + "==================================================" + RESET);
        System.out.println(CYAN + BOLD + "     SMART HOME ALARM SYSTEM SIMULATOR (Pekko)    " + RESET);
        System.out.println(CYAN + BOLD + "==================================================" + RESET);
        System.out.println("Default PIN is: " + GREEN + DEFAULT_PIN + RESET);
        System.out.println("Exit Delay: " + YELLOW + EXIT_DELAY.toSeconds() + "s" + RESET + ", Entry Delay: " + YELLOW + ENTRY_DELAY.toSeconds() + "s" + RESET);
        System.out.println("Initializing actor system...");

        // Define a root behavior that acts as a guardian and spawns all child actors.
        Behavior<Void> rootBehavior = Behaviors.setup(context -> {
            // Define and Spawn Sensors configuration
            List<SensorInfo> sensorConfigs = List.of(
                    new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter"),
                    new SensorInfo("back_door", SensorType.DOOR_WINDOW, "Perimeter"),
                    new SensorInfo("living_room_motion", SensorType.MOTION, "Living Area"),
                    new SensorInfo("kitchen_window", SensorType.DOOR_WINDOW, "Living Area"),
                    new SensorInfo("bedroom_motion", SensorType.MOTION, "Sleeping Area")
            );

            // 1. Spawn Siren (conforming to AlertDevice DIP abstraction)
            ActorRef<AlertDevice.Command> siren = context.spawn(SirenActor.create(), "siren");

            // 2. Spawn Control Unit (which spawns keypad and sensors internally)
            ActorRef<ControlUnitActor.Command> controlUnit = context.spawn(
                    ControlUnitActor.create(DEFAULT_PIN, EXIT_DELAY, ENTRY_DELAY, siren, sensorConfigs),
                    "control-unit"
            );

            // Start interactive CLI loop in a separate thread so it doesn't block the actor system
            Thread cliThread = new Thread(() -> {
                try {
                    // Query the keypad and sensor references from the ControlUnitActor (ask pattern)
                    CompletionStage<ControlUnitActor.KeypadAndSensorsReport> stage = AskPattern.ask(
                            controlUnit,
                            ControlUnitActor.GetKeypadAndSensors::new,
                            Duration.ofSeconds(3),
                            context.getSystem().scheduler()
                    );
                    ControlUnitActor.KeypadAndSensorsReport report = stage.toCompletableFuture().get();
                    runCli(context.getSystem(), controlUnit, report.keypad(), report.sensors(), sensorConfigs);
                } catch (Exception e) {
                    System.err.println("Fatal error: Failed to query alarm system components: " + e.getMessage());
                    context.getSystem().terminate();
                }
            });
            cliThread.setDaemon(true);
            cliThread.start();

            return Behaviors.empty();
        });

        // Start Actor System
        ActorSystem<Void> system = ActorSystem.create(rootBehavior, "smart-home-alarm-system");
        
        // Wait for system to terminate (runs until manually stopped)
        system.getWhenTerminated();
    }

    private static void runCli(
            ActorSystem<Void> system,
            ActorRef<ControlUnitActor.Command> controlUnit,
            ActorRef<KeypadActor.Command> keypad,
            Map<String, ActorRef<SensorActor.Command>> sensors,
            List<SensorInfo> sensorConfigs
    ) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            printHelp(sensorConfigs);

            boolean running = true;
            while (running) {
                System.out.print(BOLD + "> " + RESET);
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                String[] parts = line.trim().split("\\s+");
                String command = parts[0].toLowerCase();

                switch (command) {
                    case "help":
                        printHelp(sensorConfigs);
                        break;

                    case "status":
                        printStatus(system, controlUnit);
                        break;

                    case "pin":
                        if (parts.length < 2) {
                            System.out.println(RED + "Usage: pin <PIN_CODE>" + RESET);
                        } else {
                            keypad.tell(new KeypadActor.DirectPinSubmit(parts[1]));
                        }
                        break;

                    case "press":
                        if (parts.length < 2 || parts[1].length() != 1) {
                            System.out.println(RED + "Usage: press <single_character>" + RESET);
                        } else {
                            keypad.tell(new KeypadActor.PressKey(parts[1].charAt(0)));
                        }
                        break;

                    case "select":
                        if (parts.length < 2) {
                            System.out.println(RED + "Usage: select <zone>" + RESET);
                        } else {
                            // Reassemble zone name if it contains spaces (e.g. Living Area)
                            String zone = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
                            keypad.tell(new KeypadActor.SelectZone(zone));
                        }
                        break;

                    case "deselect":
                        if (parts.length < 2) {
                            System.out.println(RED + "Usage: deselect <zone>" + RESET);
                        } else {
                            String zone = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
                            keypad.tell(new KeypadActor.DeselectZone(zone));
                        }
                        break;

                    case "clear":
                        keypad.tell(new KeypadActor.ClearZoneSelection());
                        break;

                    case "trigger":
                        if (parts.length < 2) {
                            System.out.println(RED + "Usage: trigger <sensor_id>" + RESET);
                        } else {
                            String sensorId = parts[1];
                            ActorRef<SensorActor.Command> sensor = sensors.get(sensorId);
                            if (sensor != null) {
                                sensor.tell(new SensorActor.Trigger());
                            } else {
                                System.out.println(RED + "Unknown sensor: " + sensorId + RESET);
                            }
                        }
                        break;

                    case "exit":
                    case "quit":
                        System.out.println("Terminating simulator...");
                        system.terminate();
                        running = false;
                        break;

                    case "":
                        break;

                    default:
                        System.out.println(RED + "Unknown command: '" + command + "'. Type 'help' for available commands." + RESET);
                        break;
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading CLI input: " + e.getMessage());
        }
    }

    private static void printHelp(List<SensorInfo> sensorConfigs) {
        System.out.println("\n" + CYAN + BOLD + "--- SIMULATOR COMMANDS ---" + RESET);
        System.out.println("  " + GREEN + "help" + RESET + "                    - Show this help text");
        System.out.println("  " + GREEN + "status" + RESET + "                  - Query and print current alarm status");
        System.out.println("  " + GREEN + "pin <PIN>" + RESET + "               - Directly submit a PIN (e.g., 'pin 1234')");
        System.out.println("  " + GREEN + "press <char>" + RESET + "            - Press a keypad key (digits 0-9, '*' to clear, '#' to submit)");
        System.out.println("  " + GREEN + "select <zone>" + RESET + "           - Select a zone for partial arming (e.g., 'select Perimeter')");
        System.out.println("  " + GREEN + "deselect <zone>" + RESET + "         - Deselect a zone");
        System.out.println("  " + GREEN + "clear" + RESET + "                   - Clear all zone selections");
        System.out.println("  " + GREEN + "trigger <sensor_id>" + RESET + "     - Simulate physical sensor trigger");
        System.out.println("  " + GREEN + "exit / quit" + RESET + "             - Exit the simulation");
        System.out.println();
        System.out.println(CYAN + BOLD + "--- CONFIGURED SENSORS ---" + RESET);
        for (SensorInfo s : sensorConfigs) {
            System.out.printf("  - ID: %-20s Type: %-12s Zone: %s\n", 
                    YELLOW + s.id() + RESET, BLUE + s.type() + RESET, s.zone());
        }
        System.out.println();
    }

    private static void printStatus(ActorSystem<Void> system, ActorRef<ControlUnitActor.Command> controlUnit) {
        try {
            CompletionStage<ControlUnitActor.StateReport> stage = AskPattern.ask(
                    controlUnit,
                    ControlUnitActor.QueryState::new,
                    Duration.ofSeconds(2),
                    system.scheduler()
                    
            );

            ControlUnitActor.StateReport report = stage.toCompletableFuture().get();

            String stateStr;
            switch (report.state()) {
                case DISARMED -> stateStr = GREEN + "🔓 DISARMED" + RESET;
                case EXIT_DELAY -> stateStr = YELLOW + "⏳ EXIT DELAY" + RESET;
                case ARMED -> stateStr = BLUE + "🔒 ARMED" + RESET;
                case ENTRY_DELAY -> stateStr = YELLOW + "⏳ ENTRY DELAY" + RESET;
                case ALARM -> stateStr = RED + "🚨 ALARM (EMERGENCY)" + RESET;
                default -> stateStr = report.state().name();
            }

            System.out.println("\n" + CYAN + BOLD + "--- ALARM SYSTEM STATUS REPORT ---" + RESET);
            System.out.println("  State:           " + stateStr);
            System.out.println("  Arming Mode:     " + (report.fullyArmed() ? "Full (All zones active)" : "Partial (Only selected zones active)"));
            System.out.println("  Active Zones:    " + (report.fullyArmed() ? "ALL" : report.activeZones().isEmpty() ? "None (system is disarmed)" : report.activeZones()));
            System.out.println("-----------------------------------\n");
        } catch (Exception e) {
            System.out.println(RED + "Failed to retrieve status: " + e.getMessage() + RESET);
        }
    }
}
