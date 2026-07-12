package pcd.dttt.client;

import java.awt.GraphicsEnvironment;
import javax.swing.UIManager;

/**
 * Main entry point for the Tic-Tac-Toe client.
 * Decides whether to launch in GUI or CLI mode based on flags and headless state,
 * instantiating the GameController logic layer and injecting it into the UIs.
 */
public class ClientMain {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 1099;

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
