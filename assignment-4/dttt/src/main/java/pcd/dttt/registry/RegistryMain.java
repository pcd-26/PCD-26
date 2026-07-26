package pcd.dttt.registry;

import java.rmi.registry.LocateRegistry;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point that launches a standalone Java RMI registry process.
 *
 * <p>Running the RMI registry in a dedicated process allows the registry, game server,
 * and client applications to be started, restarted, and monitored independently.</p>
 */
public final class RegistryMain {
    private static final Logger LOGGER = Logger.getLogger(RegistryMain.class.getName());

    /** Default port for the standalone RMI registry. */
    private static final int DEFAULT_PORT = 1099;

    /** Private constructor to prevent instantiation of utility class. */
    private RegistryMain() {}

    /**
     * Starts the RMI registry on the specified port (or default 1099) and keeps the JVM running.
     *
     * @param args optional command-line argument specifying the custom RMI registry port number
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid registry port '" + args[0] + "', using default " + DEFAULT_PORT + ".");
            }
        }

        try {
            LocateRegistry.createRegistry(port);
            System.out.println("RMI registry started on port " + port + ".");
            System.out.println("Press Ctrl+C to terminate the registry process.");

            // Keep main thread alive until process termination
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.info("Registry process interrupted, shutting down.");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Registry failed to start due to exception", e);
            System.exit(1);
        }
    }
}
