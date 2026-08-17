package pcd.dttt.client;

import java.awt.GraphicsEnvironment;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.UIManager;
import pcd.dttt.common.Lobby;

// Starts the GUI or CLI client depending on the environment.
public class ClientMain {
    private static final Logger LOGGER = Logger.getLogger(ClientMain.class.getName());

    // Prevents manual instantiation.
    private ClientMain() {}

    // Parses startup arguments and selects the client mode.
    public static void main(String[] commandLineArgs) {
        boolean forceCliMode = false;
        String registryHost = GameControllerImpl.DEFAULT_REGISTRY_HOST;
        int registryPort = GameControllerImpl.DEFAULT_REGISTRY_PORT;
        String lobbyBindingName = Lobby.DEFAULT_BINDING_NAME;

        int positionalArgumentIndex = 0;
        for (String argument : commandLineArgs) {
            if (argument.equalsIgnoreCase("--cli")) {
                forceCliMode = true;
            } else if (!argument.startsWith("-")) {
                if (positionalArgumentIndex == 0) {
                    registryHost = argument;
                } else if (positionalArgumentIndex == 1) {
                    try {
                        registryPort = Integer.parseInt(argument);
                    } catch (NumberFormatException exception) {
                        System.err.println("Invalid port '" + argument + "', using default " + registryPort + ".");
                    }
                } else if (positionalArgumentIndex == 2) {
                    lobbyBindingName = argument;
                }
                positionalArgumentIndex++;
            }
        }

        boolean runningHeadless = GraphicsEnvironment.isHeadless();

        if (forceCliMode || runningHeadless) {
            if (runningHeadless && !forceCliMode) {
                System.out.println("Headless environment detected. Starting in CLI mode...");
            }
            startCliMode(registryHost, registryPort, lobbyBindingName);
        } else {
            startGuiMode(registryHost, registryPort, lobbyBindingName);
        }
    }

    // Starts the Swing client on the EDT.
    private static void startGuiMode(String registryHost, int registryPort, String lobbyBindingName) {
        System.out.println("Launching Graphic User Interface...");
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception exception) {
                // Fall back to the default Swing look and feel.
            }
            GameController controller = new GameControllerImpl();
            GUIClient guiClient = new GUIClient(controller, registryHost, registryPort, lobbyBindingName);
            guiClient.setVisible(true);
        });
    }

    // Starts the text client.
    private static void startCliMode(String registryHost, int registryPort, String lobbyBindingName) {
        try {
            GameController controller = new GameControllerImpl();
            CLIClient cliClient = new CLIClient(controller, registryHost, registryPort, lobbyBindingName);
            cliClient.start();
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "CLI mode launch failed for target RMI registry at "
                + registryHost + ":" + registryPort + " (" + lobbyBindingName + ")", exception);
            System.exit(1);
        }
    }
}
