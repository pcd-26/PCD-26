package pcd.dttt;

import java.util.Arrays;
import java.util.Locale;
import pcd.dttt.client.ClientMain;
import pcd.dttt.registry.RegistryMain;
import pcd.dttt.server.ServerMain;

/**
 * Unified entry point for the Distributed Tic-Tac-Toe application.
 * Routes execution to RegistryMain, ServerMain, or ClientMain.
 */
public class Main {
    /** Private constructor to prevent instantiation of utility class. */
    private Main() {}
    /**
     * Unified entry point. Delegates execution depending on the first argument.
     *
     * @param args command-line arguments. If the first argument is "registry", runs the RMI registry.
     *             If the first argument is "server", runs the RMI server.
     *             If the first argument is "client", runs the RMI client.
     *             Otherwise, defaults to client mode and prints usage.
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.out.println("No command specified. Defaulting to client mode...\n");
            ClientMain.main(args);
            return;
        }

        String command = args[0].toLowerCase(Locale.ROOT);
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        switch (command) {
            case "registry" -> RegistryMain.main(subArgs);
            case "server" -> ServerMain.main(subArgs);
            case "client" -> ClientMain.main(subArgs);
            default -> {
                printUsage();
                System.out.println("Unrecognized command '" + args[0] + "'. Defaulting to client mode...\n");
                ClientMain.main(args);
            }
        }
    }

    /**
     * Prints CLI command usage information to standard output.
     */
    private static void printUsage() {
        System.out.println("Distributed Tic-Tac-Toe RMI Application");
        System.out.println("Usage:");
        System.out.println("  java -jar target/distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar registry [port]");
        System.out.println("  java -jar target/distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar server [registryHost] [registryPort] [serviceName]");
        System.out.println("  java -jar target/distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar client [host] [port] [serviceName] [--cli]");
        System.out.println();
    }
}
