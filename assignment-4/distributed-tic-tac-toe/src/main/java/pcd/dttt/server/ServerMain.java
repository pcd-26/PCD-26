package pcd.dttt.server;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.logging.Level;
import java.util.logging.Logger;
import pcd.dttt.common.Lobby;

// Starts the RMI server and binds the lobby.
public class ServerMain {
    private static final Logger LOGGER = Logger.getLogger(ServerMain.class.getName());
    private static final String DEFAULT_REGISTRY_HOST = "localhost";
    private static final int DEFAULT_REGISTRY_PORT = 1099;

    // Prevents manual instantiation.
    private ServerMain() {}

    // Connects to the registry, exports the lobby, and keeps the server alive.
    public static void main(String[] commandLineArgs) {
        String registryHost = DEFAULT_REGISTRY_HOST;
        int registryPort = DEFAULT_REGISTRY_PORT;
        String lobbyBindingName = Lobby.DEFAULT_BINDING_NAME;

        int positionalArgumentIndex = 0;
        for (String argument : commandLineArgs) {
            if (!argument.startsWith("-")) {
                if (positionalArgumentIndex == 0) {
                    registryHost = argument;
                } else if (positionalArgumentIndex == 1) {
                    try {
                        registryPort = Integer.parseInt(argument);
                    } catch (NumberFormatException exception) {
                        LOGGER.warning("Invalid registry port '" + argument
                            + "', using default " + DEFAULT_REGISTRY_PORT + ".");
                    }
                } else if (positionalArgumentIndex == 2) {
                    lobbyBindingName = argument;
                }
                positionalArgumentIndex++;
            }
        }

        LobbyImpl lobbyService = null;
        try {
            Registry remoteRegistry = LocateRegistry.getRegistry(registryHost, registryPort);

            // Create the remote lobby object managed by this server.
            System.out.println("Creating Lobby instance...");
            lobbyService = new LobbyImpl();

            // Publish the lobby under the chosen binding name.
            System.out.println("Binding Lobby to registry as '" + lobbyBindingName + "'...");
            remoteRegistry.rebind(lobbyBindingName, lobbyService);

            final Registry shutdownRegistry = remoteRegistry;
            final String shutdownBindingName = lobbyBindingName;
            LobbyImpl shutdownLobbyService = lobbyService;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    shutdownRegistry.unbind(shutdownBindingName);
                } catch (Exception exception) {
                    // Ignore registry cleanup failures during shutdown.
                }
                shutdownLobbyService.close();
            }));

            System.out.println("\n=============================================");
            System.out.println("  Tic-Tac-Toe RMI Server is running!");
            System.out.println("  Registry Host: " + registryHost);
            System.out.println("  Registry Port: " + registryPort);
            System.out.println("  Bound name: " + lobbyBindingName);
            System.out.println("=============================================\n");
            System.out.println("Press Ctrl+C to terminate the server.");

            // Keep the server JVM alive until the process is interrupted.
            Thread.currentThread().join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.info("Server main thread interrupted, shutting down.");
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Server failed to start due to exception", exception);
            if (lobbyService != null) {
                lobbyService.close();
            }
            System.exit(1);
        }
    }
}
