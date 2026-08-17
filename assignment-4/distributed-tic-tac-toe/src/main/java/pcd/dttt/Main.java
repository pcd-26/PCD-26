package pcd.dttt;

import java.util.Arrays;
import java.util.Locale;
import pcd.dttt.client.ClientMain;
import pcd.dttt.registry.RegistryMain;
import pcd.dttt.server.ServerMain;

// Routes the application to registry, server, or client mode.
public class Main {
    // Prevents manual instantiation.
    private Main() {}

    // Dispatches execution based on the first CLI argument.
    public static void main(String[] commandLineArgs) {
        if (commandLineArgs.length == 0) {
            printUsage();
            System.out.println("No command specified. Defaulting to client mode...\n");
            ClientMain.main(commandLineArgs);
            return;
        }

        String executionMode = commandLineArgs[0].toLowerCase(Locale.ROOT);
        String[] delegatedArgs = Arrays.copyOfRange(commandLineArgs, 1, commandLineArgs.length);

        switch (executionMode) {
            case "registry" -> RegistryMain.main(delegatedArgs);
            case "server" -> ServerMain.main(delegatedArgs);
            case "client" -> ClientMain.main(delegatedArgs);
            default -> {
                printUsage();
                System.out.println("Unrecognized command '" + commandLineArgs[0] + "'. Defaulting to client mode...\n");
                ClientMain.main(commandLineArgs);
            }
        }
    }

    // Prints the supported startup commands.
    private static void printUsage() {
        System.out.println("Distributed Tic-Tac-Toe RMI Application");
        System.out.println("Usage:");
        System.out.println("  java -jar target/distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar registry [port]");
        System.out.println("  java -jar target/distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar server [registryHost] [registryPort] [serviceName]");
        System.out.println("  java -jar target/distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar client [host] [port] [serviceName] [--cli]");
        System.out.println();
    }
}
