package pcd.dttt.registry;

import java.rmi.registry.LocateRegistry;
import java.util.logging.Level;
import java.util.logging.Logger;

// Starts the standalone RMI registry process.
public final class RegistryMain {
    private static final Logger LOGGER = Logger.getLogger(RegistryMain.class.getName());
    private static final int DEFAULT_REGISTRY_PORT = 1099;

    // Prevents manual instantiation.
    private RegistryMain() {}

    // Starts the registry and keeps the process alive.
    public static void main(String[] commandLineArgs) {
        int registryPort = DEFAULT_REGISTRY_PORT;
        if (commandLineArgs.length > 0) {
            try {
                registryPort = Integer.parseInt(commandLineArgs[0]);
            } catch (NumberFormatException exception) {
                LOGGER.warning("Invalid registry port '" + commandLineArgs[0]
                    + "', using default " + DEFAULT_REGISTRY_PORT + ".");
            }
        }

        try {
            LocateRegistry.createRegistry(registryPort);
            System.out.println("RMI registry started on port " + registryPort + ".");
            System.out.println("Press Ctrl+C to terminate the registry process.");

            // Keep the registry JVM alive until the process is interrupted.
            Thread.currentThread().join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.info("Registry process interrupted, shutting down.");
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Registry failed to start due to exception", exception);
            System.exit(1);
        }
    }
}
