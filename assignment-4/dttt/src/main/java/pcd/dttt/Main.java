package pcd.dttt;

import java.util.Arrays;
import pcd.dttt.client.ClientMain;
import pcd.dttt.registry.RegistryMain;
import pcd.dttt.server.ServerMain;

/**
 * Unified entry point for the Distributed Tic-Tac-Toe application.
 * Routes execution to RegistryMain, ServerMain, or ClientMain.
 */
public class Main {
    /**
     * Unified entry point. Delegates execution depending on the first argument.
     *
     * @param args command-line arguments. If the first argument is "registry", runs the RMI registry.
     *             If the first argument is "server", runs the RMI server.
     *             If the first argument is "client", runs the RMI client.
     *             Otherwise, defaults to client mode and prints usage.
     */
    public static void main(String[] args) {
        if (args.length > 0) {
            String command = args[0].toLowerCase();
            String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

            if (command.equals("registry")) {
                RegistryMain.main(subArgs);
                return;
            }
            if (command.equals("server")) {
                ServerMain.main(subArgs);
                return;
            } else if (command.equals("client")) {
                ClientMain.main(subArgs);
                return;
            }
        }

        // Default fallback if no valid command is specified
        printUsage();
        System.out.println("No command specified or unrecognized command. Defaulting to client mode...\n");
        ClientMain.main(args);
    }

    /**
     * Prints CLI command usage information to standard output.
     */
    private static void printUsage() {
        System.out.println("Distributed Tic-Tac-Toe RMI Application");
        System.out.println("Usage:");
        System.out.println("  java -jar target/distributed-ttt-1.0-SNAPSHOT-jar-with-dependencies.jar registry [port]");
        System.out.println("  java -jar target/distributed-ttt-1.0-SNAPSHOT-jar-with-dependencies.jar server [registryHost] [registryPort] [serviceName]");
        System.out.println("  java -jar target/distributed-ttt-1.0-SNAPSHOT-jar-with-dependencies.jar client [host] [port] [serviceName] [--cli]");
        System.out.println();
    }
}
