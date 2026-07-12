package pcd.dttt.client;

import java.awt.GraphicsEnvironment;
import javax.swing.UIManager;

/**
 * Main entry point for the Tic-Tac-Toe client.
 * Decides whether to launch in GUI or CLI mode based on flags and headless state,
 * instantiating the GameController logic layer and injecting it into the UIs.
 */
public class ClientMain {
    /** The default host IP/address for the RMI server connection. */
    private static final String DEFAULT_HOST = "localhost";

    /** The default port for the RMI server connection. */
    private static final int DEFAULT_PORT = 1099;

    /**
     * Entry point for the Client application.
     * Parses arguments to determine connection target and whether CLI mode is forced.
     * Launches the GUI or CLI client accordingly.
     *
     * @param args command-line arguments: [host] [port] [--cli]
     */
    public static void main(String[] args) {
        boolean forceCli = false;
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

        // Parse command line arguments
        // Usage: client [host] [port] [--cli]
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--cli")) {
                forceCli = true;
            } else if (arg.matches("\\d+")) {
                try {
                    port = Integer.parseInt(arg);
                } catch (NumberFormatException e) {
                    // Ignore
                }
            } else if (!arg.startsWith("-")) {
                host = arg;
            }
        }

        boolean headless = GraphicsEnvironment.isHeadless();

        if (forceCli || headless) {
            if (headless && !forceCli) {
                System.out.println("Headless environment detected. Starting in CLI mode...");
            }
            startCliMode(host, port);
        } else {
            startGuiMode();
        }
    }

    /**
     * Starts the Graphical User Interface client.
     * Configures the system look and feel, constructs the GUI frame,
     * and makes it visible on the Event Dispatch Thread (EDT).
     */
    private static void startGuiMode() {
        System.out.println("Launching Graphic User Interface...");
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Fallback to default Swing Look & Feel
            }
            GameController controller = new GameControllerImpl();
            GUIClient gui = new GUIClient(controller);
            gui.setVisible(true);
        });
    }

    /**
     * Starts the Command Line Interface client.
     * Establishes connection parameters, initializes the CLI, and starts the scanner loop.
     *
     * @param host the remote server hostname
     * @param port the remote server RMI port
     */
    private static void startCliMode(String host, int port) {
        try {
            GameController controller = new GameControllerImpl();
            CLIClient cli = new CLIClient(controller, host, port);
            cli.start();
        } catch (Exception e) {
            System.err.println("CLI mode launch failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
