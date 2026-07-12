package pcd.dttt.client;

import java.awt.GraphicsEnvironment;
import javax.swing.UIManager;
import pcd.dttt.common.Lobby;

/**
 * Main entry point for the Tic-Tac-Toe client.
 * Decides whether to launch in GUI or CLI mode based on flags and headless state,
 * instantiating the GameController logic layer and injecting it into the UIs.
 */
public class ClientMain {

    /**
     * Entry point for the Client application.
     * Parses arguments to determine connection target and whether CLI mode is forced.
     * Launches the GUI or CLI client accordingly.
     *
     * @param args command-line arguments: [host] [port] [serviceName] [--cli]
     */
    public static void main(String[] args) {
        boolean forceCli = false;
        String host = GameControllerImpl.DEFAULT_REGISTRY_HOST;
        int port = GameControllerImpl.DEFAULT_REGISTRY_PORT;
        String serviceName = Lobby.DEFAULT_BINDING_NAME;

        // Parse command line arguments
        // Usage: client [host] [port] [serviceName] [--cli]
        int positionalIndex = 0;
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--cli")) {
                forceCli = true;
            } else if (!arg.startsWith("-")) {
                if (positionalIndex == 0) {
                    host = arg;
                } else if (positionalIndex == 1) {
                    try {
                        port = Integer.parseInt(arg);
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid port '" + arg + "', using default " + port + ".");
                    }
                } else if (positionalIndex == 2) {
                    serviceName = arg;
                }
                positionalIndex++;
            }
        }

        boolean headless = GraphicsEnvironment.isHeadless();

        if (forceCli || headless) {
            if (headless && !forceCli) {
                System.out.println("Headless environment detected. Starting in CLI mode...");
            }
            startCliMode(host, port, serviceName);
        } else {
            startGuiMode(host, port, serviceName);
        }
    }

    /**
     * Starts the Graphical User Interface client.
     * Configures the system look and feel, constructs the GUI frame,
     * and makes it visible on the Event Dispatch Thread (EDT).
     */
    private static void startGuiMode(String host, int port, String serviceName) {
        System.out.println("Launching Graphic User Interface...");
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Fallback to default Swing Look & Feel
            }
            GameController controller = new GameControllerImpl();
            GUIClient gui = new GUIClient(controller, host, port, serviceName);
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
    private static void startCliMode(String host, int port, String serviceName) {
        try {
            GameController controller = new GameControllerImpl();
            CLIClient cli = new CLIClient(controller, host, port, serviceName);
            cli.start();
        } catch (Exception e) {
            System.err.println("CLI mode launch failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
