package pcd.dttt.server;

import java.rmi.registry.Registry;
import pcd.dttt.common.Lobby;

/**
 * Main entry point for starting the Tic-Tac-Toe RMI Server.
 */
public class ServerMain {
    /** The default RMI registry host. */
    private static final String DEFAULT_REGISTRY_HOST = "localhost";

    /** The default RMI registry port. */
    private static final int DEFAULT_PORT = 1099;

    /**
     * Entry point to launch the RMI server.
     * Connects to an existing registry, exports the Lobby remote object,
     * and registers it under the configured binding name.
     *
     * @param args command-line arguments: [registryHost] [registryPort] [serviceName]
     */
    public static void main(String[] args) {
        String registryHost = DEFAULT_REGISTRY_HOST;
        int registryPort = DEFAULT_PORT;
        String serviceName = Lobby.DEFAULT_BINDING_NAME;

        int positionalIndex = 0;
        for (String arg : args) {
            if (!arg.startsWith("-")) {
                if (positionalIndex == 0) {
                    registryHost = arg;
                } else if (positionalIndex == 1) {
                    try {
                        registryPort = Integer.parseInt(arg);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid registry port '" + arg + "', using default " + DEFAULT_PORT + ".");
                    }
                } else if (positionalIndex == 2) {
                    serviceName = arg;
                }
                positionalIndex++;
            }
        }

        LobbyImpl lobby = null;
        try {
            Registry registry = java.rmi.registry.LocateRegistry.getRegistry(registryHost, registryPort);

            System.out.println("Creating Lobby instance...");
            lobby = new LobbyImpl();

            System.out.println("Binding Lobby to registry as '" + serviceName + "'...");
            registry.rebind(serviceName, lobby);

            LobbyImpl finalLobby = lobby;
            Registry finalRegistry = registry;
            String finalServiceName = serviceName;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    finalRegistry.unbind(finalServiceName);
                } catch (Exception e) {
                    // Ignore registry cleanup failures during shutdown.
                }
                finalLobby.close();
            }));

            System.out.println("\n=============================================");
            System.out.println("  Tic-Tac-Toe RMI Server is running!");
            System.out.println("  Registry Host: " + registryHost);
            System.out.println("  Registry Port: " + registryPort);
            System.out.println("  Bound name: " + serviceName);
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
            if (lobby != null) {
                lobby.close();
            }
            System.exit(1);
        }
    }
}
