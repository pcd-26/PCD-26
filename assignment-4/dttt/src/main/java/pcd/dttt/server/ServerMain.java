package pcd.dttt.server;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import pcd.dttt.common.Lobby;

/**
 * Main entry point for starting the Tic-Tac-Toe RMI Server.
 */
public class ServerMain {
    /** The default port on which the RMI registry is started. */
    private static final int DEFAULT_PORT = 1099;

    /** The name under which the Lobby remote object is bound in the registry. */
    private static final String REGISTRY_NAME = "Lobby";

    /**
     * Entry point to launch the RMI server.
     * Configures the port, creates and exports the Lobby remote object,
     * and registers it into the RMI registry.
     *
     * @param args command-line arguments: args[0] optionally specifies the registry port
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number specified. Using default: " + DEFAULT_PORT);
            }
        }

        try {
            System.out.println("Starting RMI Registry on port " + port + "...");
            Registry registry = LocateRegistry.createRegistry(port);

            System.out.println("Creating Lobby instance...");
            Lobby lobby = new LobbyImpl();

            System.out.println("Binding Lobby to registry as '" + REGISTRY_NAME + "'...");
            registry.rebind(REGISTRY_NAME, lobby);

            System.out.println("\n=============================================");
            System.out.println("  Tic-Tac-Toe RMI Server is running!");
            System.out.println("  Registry Port: " + port);
            System.out.println("  Bound name: " + REGISTRY_NAME);
            System.out.println("=============================================\n");
            System.out.println("Press Ctrl+C to terminate the server.");

            // Keep main thread alive
            Object lock = new Object();
            synchronized (lock) {
                while (true) {
                    lock.wait();
                }
            }
        } catch (Exception e) {
            System.err.println("Server failed to start due to exception:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
