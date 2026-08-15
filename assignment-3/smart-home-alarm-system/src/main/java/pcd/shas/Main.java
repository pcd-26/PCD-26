package pcd.shas;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import pcd.shas.common.Zone;

import java.util.Locale;
import java.util.Scanner;
import java.util.Set;

public final class Main {

    private static final String SYSTEM_NAME = "shas-cli";

    private Main() {}

    public static void main(String[] args) {
        AlarmConfiguration alarmConfiguration = AlarmConfiguration.from(ConfigFactory.load());
        ActorSystem<RootActor.Command> system = ActorSystem.create(RootActor.create(alarmConfiguration), SYSTEM_NAME);

        printHelp();
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                if (!handleCommand(scanner.nextLine(), system)) {
                    break;
                }
            }
        } finally {
            system.tell(new RootActor.Stop());
            system.getWhenTerminated().toCompletableFuture().join();
        }
    }

    private static boolean handleCommand(String input, ActorSystem<RootActor.Command> system) {
        String command = input.trim();
        if (command.isEmpty()) {
            return true;
        }

        if (command.equalsIgnoreCase("quit") || command.equalsIgnoreCase("exit")) {
            return false;
        }

        if (command.equalsIgnoreCase("help")) {
            printHelp();
            return true;
        }

        if (command.equalsIgnoreCase("status")) {
            system.tell(new RootActor.PrintStatus());
            return true;
        }

        if (command.startsWith("arm full ")) {
            system.tell(new RootActor.RequestFullArming(command.substring("arm full ".length()).trim()));
            return true;
        }

        if (command.startsWith("arm partial ")) {
            try {
                String[] parts = command.substring("arm partial ".length()).trim().split("\\s+", 2);
                if (parts.length < 2) {
                    throw new IllegalArgumentException("PIN and at least one zone are required");
                }
                system.tell(new RootActor.RequestPartialArming(parts[0], parseZones(parts[1])));
            } catch (IllegalArgumentException exception) {
                System.out.println("[CLI] Invalid partial arming request.");
                System.out.println("[CLI] Use: arm partial <PIN> PERIMETER GROUND_FLOOR");
                System.out.println("[CLI] Available zones: PERIMETER, GROUND_FLOOR, LIVING_AREA, SLEEPING_AREA");
            }
            return true;
        }

        if (command.startsWith("pin ")) {
            system.tell(new RootActor.SubmitPin(command.substring("pin ".length()).trim()));
            return true;
        }

        if (command.equalsIgnoreCase("front door")) {
            system.tell(new RootActor.ActivateFrontDoor());
            return true;
        }

        if (command.equalsIgnoreCase("ground floor")) {
            system.tell(new RootActor.ActivateGroundFloor());
            return true;
        }

        if (command.equalsIgnoreCase("living room")) {
            system.tell(new RootActor.ActivateLivingRoom());
            return true;
        }

        if (command.equalsIgnoreCase("bedroom")) {
            system.tell(new RootActor.ActivateBedroom());
            return true;
        }

        System.out.println("[CLI] Unknown command: " + command);
        System.out.println("[CLI] Type 'help' for the command list.");
        return true;
    }

    private static Set<Zone> parseZones(String input) {
        String normalizedInput = input.toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace(',', ' ')
            .trim();

        if (normalizedInput.isEmpty()) {
            throw new IllegalArgumentException("At least one zone is required");
        }

        return Set.of(normalizedInput.split("\\s+"))
            .stream()
            .map(Zone::valueOf)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static void printHelp() {
        System.out.println("""
            Smart Home Alarm System CLI
            Type a command and press ENTER. Replace <PIN> with the configured PIN.

            Commands:
              arm full <PIN>
                Validate the PIN and start the exit delay with every zone active.

              arm partial <PIN> PERIMETER GROUND_FLOOR
                Validate the PIN and start the exit delay with only the listed zones active.
                Available zones: PERIMETER, GROUND_FLOOR, LIVING_AREA, SLEEPING_AREA.

              pin <PIN>
                Disarm the system, cancel the exit/entry delay, or stop the siren.

              front door
                Simulate a door/window sensor event in the PERIMETER zone.

              ground floor
                Simulate a door/window sensor event in the GROUND_FLOOR zone.

              living room
                Simulate a motion sensor event in the LIVING_AREA zone.

              bedroom
                Simulate a motion sensor event in the SLEEPING_AREA zone.

              status
                Print the current alarm state and whether the siren is active.

              help
                Show this command list.

              quit
                Stop the actor system and exit.
            """);
    }
}
