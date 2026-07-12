package pcd.dttt.registry;

import java.rmi.registry.LocateRegistry;

/**
 * Entry point that starts a standalone Java RMI registry.
 *
 * <p>This process is intentionally separate from the server JVM so the registry, server,
 * and clients can be launched independently when needed.</p>
 */
public final class RegistryMain {
    /** Default port for the standalone registry. */
    private static final int DEFAULT_PORT = 1099;

    private RegistryMain() {
        // Utility class.
    }

    /**
     * Starts the RMI registry on the requested port and keeps the JVM alive.
     *
     * @param args optional registry port
     */
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid registry port '" + args[0] + "', using default " + DEFAULT_PORT + ".");
            }
        }

        try {
            LocateRegistry.createRegistry(port);
            System.out.println("RMI registry started on port " + port + ".");
            System.out.println("Press Ctrl+C to terminate the registry process.");

            Object lock = new Object();
            synchronized (lock) {
                while (true) {
                    lock.wait();
                }
            }
        } catch (Exception e) {
            System.err.println("Registry failed to start due to exception:");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
