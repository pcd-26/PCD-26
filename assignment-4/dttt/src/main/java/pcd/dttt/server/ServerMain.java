package pcd.dttt.server;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.logging.Level;
import java.util.logging.Logger;
import pcd.dttt.common.Lobby;

/**
 * Main entry point for starting the Tic-Tac-Toe RMI Server.
 */
public class ServerMain {
    private static final Logger LOGGER = Logger.getLogger(ServerMain.class.getName());

    /** Private constructor to prevent instantiation of utility class. */
    private ServerMain() {}

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
                        LOGGER.warning("Invalid registry port '" + arg + "', using default " + DEFAULT_PORT + ".");
                    }
                } else if (positionalIndex == 2) {
                    serviceName = arg;
                }
                positionalIndex++;
            }
        }

        LobbyImpl lobby = null;
        try {
            Registry registry = LocateRegistry.getRegistry(registryHost, registryPort);

            System.out.println("Creating Lobby instance...");
            lobby = new LobbyImpl();

            System.out.println("Binding Lobby to registry as '" + serviceName + "'...");
            registry.rebind(serviceName, lobby);

            final Registry targetRegistry = registry;
            final String boundServiceName = serviceName;
            LobbyImpl activeLobby = lobby;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    targetRegistry.unbind(boundServiceName);
                } catch (Exception e) {
                    // Ignore registry cleanup failures during shutdown.
                }
                activeLobby.close();
            }));

            System.out.println("\n=============================================");
            System.out.println("  Tic-Tac-Toe RMI Server is running!");
            System.out.println("  Registry Host: " + registryHost);
            System.out.println("  Registry Port: " + registryPort);
            System.out.println("  Bound name: " + serviceName);
            System.out.println("=============================================\n");
            System.out.println("Press Ctrl+C to terminate the server.");

            // Keep main thread alive until process is interrupted
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Server main thread interrupted, shutting down.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Server failed to start due to exception", e);
            if (lobby != null) {
                lobby.close();
            }
            System.exit(1);
        }
    }
}
