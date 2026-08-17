package pcd.dttt.client;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.Serial;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.GameStatus;
import pcd.dttt.common.exceptions.InvalidMoveException;
import pcd.dttt.common.exceptions.NotYourTurnException;

// Swing client for the distributed Tic-Tac-Toe game.
public class GUIClient extends JFrame implements GameEventListener {
    @Serial
    private static final long serialVersionUID = 1L;

    // Sleek Dark Theme Color Palette
    // Main background color.
    private static final Color COLOR_BG = new Color(30, 30, 46);
    // Secondary panel color.
    private static final Color COLOR_PANEL_BG = new Color(49, 50, 68);
    // Default button color.
    private static final Color COLOR_BTN_BG = new Color(17, 17, 27);
    // Default text color.
    private static final Color COLOR_FG = new Color(205, 214, 244);
    // Positive status color.
    private static final Color COLOR_STATUS = new Color(166, 227, 161);
    // Waiting status color.
    private static final Color COLOR_WARN = new Color(249, 226, 175);

    // High-Contrast Button Colors (harmonized with Catppuccin Mocha dark theme)
    // Primary action color.
    private static final Color COLOR_BTN_PRIMARY = new Color(45, 79, 124);
    // Join action color.
    private static final Color COLOR_BTN_SUCCESS = new Color(45, 94, 64);
    // Leave action color.
    private static final Color COLOR_BTN_DANGER = new Color(124, 53, 67);
    // Secondary action color.
    private static final Color COLOR_BTN_SECONDARY = new Color(69, 71, 90);

    // Color used for X.
    private static final Color COLOR_X = new Color(255, 85, 120);
    // Color used for O.
    private static final Color COLOR_O = new Color(85, 170, 255);

    // Manages screen switching.
    private final CardLayout cardLayout;
    // Holds all screens.
    private final JPanel mainPanel;

    // Game Client Controller & State
    // Connects the UI to the distributed backend.
    private final GameController controller;
    // Default host shown in the form.
    private final String defaultHost;
    // Default port shown in the form.
    private final int defaultPort;
    // Default service name shown in the form.
    private final String defaultServiceName;
    // Local player mark.
    private char myMark = ' '; // 'X' or 'O' or ' '

    // Connection Screen Components
    // Host input field.
    private JTextField hostField;
    // Port input field.
    private JTextField portField;
    // Service name input field.
    private JTextField serviceField;
    // Player name input field.
    private JTextField nameField;
    // Connect button.
    private JButton connectBtn;

    // Lobby Screen Components
    // New room name input field.
    private JTextField newGameField;
    // Waiting rooms list.
    private JList<String> waitingGamesList;
    // Waiting rooms model.
    private DefaultListModel<String> listModel;
    // Create room button.
    private JButton createGameBtn;
    // Join room button.
    private JButton joinGameBtn;
    // Refresh rooms button.
    private JButton refreshBtn;
    // Lobby status label.
    private JLabel lobbyStatusLbl;

    // Game Screen Components
    // Board buttons.
    private JButton[][] boardButtons;
    // Match status label.
    private JLabel gameStatusLbl;
    // Opponent info label.
    private JLabel gameOpponentLbl;

    // Builds the main GUI frame.
    public GUIClient(GameController controller, String defaultHost, int defaultPort, String defaultServiceName) {
        super("Distributed Tic-Tac-Toe");
        this.controller = controller;
        this.defaultHost = defaultHost;
        this.defaultPort = defaultPort;
        this.defaultServiceName = defaultServiceName;
        this.controller.registerEventListener(this);
        
        // Configure the main window.
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(550, 650);
        setLocationRelativeTo(null);

        // Disconnect cleanly when the window closes.
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                cleanupAndExit();
            }
        });

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);
        mainPanel.setBackground(COLOR_BG);

        // Build and register every screen.
        mainPanel.add(buildConnectionScreen(), "CONNECTION");
        mainPanel.add(buildLobbyScreen(), "LOBBY");
        mainPanel.add(buildGameScreen(), "GAME");

        add(mainPanel);
        cardLayout.show(mainPanel, "CONNECTION");
    }

    // Disconnects and terminates the application.
    private void cleanupAndExit() {
        new Thread(() -> {
            controller.disconnect();
            System.exit(0);
        }).start();
    }

    // --- Screen Builders ---

    // Builds the connection screen.
    private JPanel buildConnectionScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(COLOR_BG);
        panel.setBorder(new EmptyBorder(30, 30, 30, 30));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 10, 10, 10);

        JLabel title = new JLabel("Distributed Tic-Tac-Toe", JLabel.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 28));
        title.setForeground(Color.WHITE);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        panel.add(title, gbc);

        gbc.gridwidth = 1;
        gbc.weightx = 0.3;

        JLabel hostLabel = createLabel("Server IP:");
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(hostLabel, gbc);

        hostField = createTextField(defaultHost);
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 0.7;
        panel.add(hostField, gbc);

        JLabel portLabel = createLabel("Server Port:");
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0.3;
        panel.add(portLabel, gbc);

        portField = createTextField(String.valueOf(defaultPort));
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.weightx = 0.7;
        panel.add(portField, gbc);

        JLabel serviceLabel = createLabel("Service Name:");
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0.3;
        panel.add(serviceLabel, gbc);

        serviceField = createTextField(defaultServiceName);
        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.weightx = 0.7;
        panel.add(serviceField, gbc);

        JLabel nameLabel = createLabel("Your Name:");
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.weightx = 0.3;
        panel.add(nameLabel, gbc);

        nameField = createTextField("");
        gbc.gridx = 1;
        gbc.gridy = 4;
        gbc.weightx = 0.7;
        panel.add(nameField, gbc);

        connectBtn = createStyledButton("Connect to Lobby", COLOR_BTN_PRIMARY);
        connectBtn.addActionListener(this::handleConnect);
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(20, 10, 10, 10);
        panel.add(connectBtn, gbc);

        return panel;
    }

    // Builds the lobby screen.
    private JPanel buildLobbyScreen() {
        JPanel panel = createScreenPanel();

        // Top Panel - Header
        JPanel topPanel = createHeaderPanel();
        JLabel title = new JLabel("Game Lobby", JLabel.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        title.setForeground(Color.WHITE);
        lobbyStatusLbl = new JLabel("Welcome!", JLabel.CENTER);
        lobbyStatusLbl.setForeground(COLOR_STATUS);
        lobbyStatusLbl.setFont(new Font("SansSerif", Font.PLAIN, 14));
        topPanel.add(title);
        topPanel.add(lobbyStatusLbl);
        panel.add(topPanel, BorderLayout.NORTH);

        // Center Panel - List of games
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.setBackground(COLOR_PANEL_BG);
        centerPanel.setBorder(BorderFactory.createTitledBorder(
                new LineBorder(new Color(69, 71, 90), 1, true), "Available Games", 
                0, 0, new Font("SansSerif", Font.BOLD, 14), Color.WHITE
        ));
        
        listModel = new DefaultListModel<>();
        waitingGamesList = new JList<>(listModel);
        waitingGamesList.setBackground(COLOR_BTN_BG);
        waitingGamesList.setForeground(Color.WHITE);
        waitingGamesList.setSelectionBackground(COLOR_BTN_PRIMARY);
        waitingGamesList.setSelectionForeground(Color.WHITE);
        waitingGamesList.setFont(new Font("SansSerif", Font.PLAIN, 15));
        waitingGamesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(waitingGamesList);
        scrollPane.setBorder(null);
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        // Refresh Button
        refreshBtn = createStyledButton("Refresh Game List", COLOR_BTN_SECONDARY);
        refreshBtn.addActionListener(e -> refreshWaitingGames());
        centerPanel.add(refreshBtn, BorderLayout.SOUTH);

        panel.add(centerPanel, BorderLayout.CENTER);

        // East Panel - Actions (Create / Join)
        JPanel eastPanel = new JPanel(new GridBagLayout());
        eastPanel.setBackground(COLOR_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 5, 10, 5);
        gbc.gridx = 0;

        // Create Section
        JLabel createLbl = createLabel("Create Room:");
        createLbl.setFont(new Font("SansSerif", Font.BOLD, 14));
        gbc.gridy = 0;
        eastPanel.add(createLbl, gbc);

        newGameField = createTextField("");
        newGameField.setPreferredSize(new Dimension(150, 30));
        gbc.gridy = 1;
        eastPanel.add(newGameField, gbc);

        createGameBtn = createStyledButton("Create Game", COLOR_BTN_PRIMARY);
        createGameBtn.addActionListener(this::handleCreateGame);
        gbc.gridy = 2;
        eastPanel.add(createGameBtn, gbc);

        // Separator space
        gbc.gridy = 3;
        gbc.insets = new Insets(20, 5, 20, 5);
        eastPanel.add(new JSeparator(JSeparator.HORIZONTAL), gbc);
        gbc.insets = new Insets(10, 5, 10, 5);

        // Join Section
        joinGameBtn = createStyledButton("Join Selected", COLOR_BTN_SUCCESS);
        joinGameBtn.addActionListener(this::handleJoinGame);
        gbc.gridy = 4;
        eastPanel.add(joinGameBtn, gbc);

        panel.add(eastPanel, BorderLayout.EAST);

        return panel;
    }

    // Builds the game screen.
    private JPanel buildGameScreen() {
        JPanel panel = createScreenPanel();

        // Top Information
        JPanel infoPanel = createHeaderPanel();
        
        gameStatusLbl = new JLabel("Waiting for match to begin...", JLabel.CENTER);
        gameStatusLbl.setFont(new Font("SansSerif", Font.BOLD, 18));
        gameStatusLbl.setForeground(COLOR_STATUS);

        gameOpponentLbl = new JLabel("Opponent: -", JLabel.CENTER);
        gameOpponentLbl.setFont(new Font("SansSerif", Font.PLAIN, 14));
        gameOpponentLbl.setForeground(Color.WHITE);

        infoPanel.add(gameStatusLbl);
        infoPanel.add(gameOpponentLbl);
        panel.add(infoPanel, BorderLayout.NORTH);

        // Center Grid 3x3
        // Create the board container.
        JPanel boardPanel = new JPanel(new GridLayout(3, 3, 10, 10));
        boardPanel.setBackground(COLOR_PANEL_BG);
        boardPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        boardButtons = new JButton[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                JButton btn = createBoardCellButton(r, c);
                boardButtons[r][c] = btn;
                boardPanel.add(btn);
            }
        }
        panel.add(boardPanel, BorderLayout.CENTER);

        // Bottom - Leave button
        // Create the leave-match button.
        JButton leaveGameBtn = createStyledButton("Leave Match", COLOR_BTN_DANGER);
        leaveGameBtn.addActionListener(this::handleLeaveGame);
        panel.add(leaveGameBtn, BorderLayout.SOUTH);

        return panel;
    }

    // --- Helper UI Factory Methods ---

    // Creates the base panel used by each screen.
    private JPanel createScreenPanel() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(COLOR_BG);
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));
        return panel;
    }

    // Creates the standard header panel.
    private JPanel createHeaderPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 5, 5));
        panel.setBackground(COLOR_BG);
        return panel;
    }

    // Creates one board cell button.
    private JButton createBoardCellButton(int row, int col) {
        JButton btn = new JButton("");
        btn.setBackground(COLOR_BTN_BG);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("SansSerif", Font.BOLD, 54));
        btn.setFocusPainted(false);
        btn.setBorder(new LineBorder(new Color(69, 71, 90), 2));
        btn.addActionListener(e -> handleBoardClick(row, col));
        btn.setEnabled(false);

        // Highlight empty cells on hover.
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                if (btn.isEnabled() && btn.getText().isEmpty()) {
                    btn.setBackground(COLOR_PANEL_BG);
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent evt) {
                if (btn.getText().isEmpty()) {
                    btn.setBackground(COLOR_BTN_BG);
                }
            }
        });

        return btn;
    }

    // Creates a styled label.
    private JLabel createLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 15));
        lbl.setForeground(Color.WHITE);
        return lbl;
    }

    // Creates a styled text field.
    private JTextField createTextField(String text) {
        JTextField tf = new JTextField(text);
        tf.setBackground(COLOR_BTN_BG);
        tf.setForeground(Color.WHITE);
        tf.setCaretColor(Color.WHITE);
        tf.setFont(new Font("SansSerif", Font.PLAIN, 15));
        tf.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(COLOR_PANEL_BG, 1, true),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        return tf;
    }

    // Creates a styled button.
    private JButton createStyledButton(String text, Color baseColor) {
        JButton btn = new JButton(text);
        btn.setBackground(baseColor);
        btn.setForeground(getReadableButtonTextColor(baseColor));
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorderPainted(false);

        btn.setFocusPainted(false);
        btn.setFont(new Font("SansSerif", Font.BOLD, 15));
        btn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(baseColor.darker(), 1, true),
                BorderFactory.createEmptyBorder(8, 15, 8, 15)
        ));
        
        // Precompute the hover color.
        Color hoverColor = getHoverColor(baseColor);

        // Apply hover effects.
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                btn.setBackground(hoverColor);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent evt) {
                btn.setBackground(baseColor);
            }
        });
        return btn;
    }

    // Chooses a readable foreground color for a button background.
    private Color getReadableButtonTextColor(Color backgroundColor) {
        double luminance = (0.2126 * backgroundColor.getRed()
            + 0.7152 * backgroundColor.getGreen()
            + 0.0722 * backgroundColor.getBlue()) / 255.0;
        return luminance < 0.45 ? Color.WHITE : COLOR_BG;
    }

    // Computes a darker hover color.
    private Color getHoverColor(Color color) {
        int r = (int)(color.getRed() * 0.85);
        int g = (int)(color.getGreen() * 0.85);
        int b = (int)(color.getBlue() * 0.85);
        return new Color(r, g, b);
    }

    // --- Event Handlers ---

    // Connects to the lobby without blocking the EDT.
    private void handleConnect(ActionEvent e) {
        String host = hostField.getText().trim();
        String portStr = portField.getText().trim();
        String serviceName = serviceField.getText().trim();
        String name = nameField.getText().trim();

        if (host.isEmpty() || portStr.isEmpty() || serviceName.isEmpty() || name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "All fields are required.", "Input Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Port must be a valid integer.", "Input Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Validate the port before starting the background connection.
        connectBtn.setEnabled(false);
        connectBtn.setText("Connecting...");

        new Thread(() -> {
            try {
                // Perform the remote connection off the EDT.
                controller.connect(host, port, serviceName, name);
                SwingUtilities.invokeLater(() -> {
                    lobbyStatusLbl.setText("Connected as: " + controller.getPlayerName());
                    cardLayout.show(mainPanel, "LOBBY");
                    refreshWaitingGames();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Failed to connect to RMI Server:\n" + ex.getMessage(),
                            "Connection Failure", JOptionPane.ERROR_MESSAGE);
                    connectBtn.setEnabled(true);
                    connectBtn.setText("Connect to Lobby");
                });
            }
        }).start();
    }

    // Refreshes the list of waiting rooms.
    private void refreshWaitingGames() {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("Refreshing...");
        new Thread(() -> {
            try {
                List<String> gamesList = controller.getWaitingGames();
                SwingUtilities.invokeLater(() -> {
                    listModel.clear();
                    for (String gameName : gamesList) {
                        listModel.addElement(gameName);
                    }
                    refreshBtn.setEnabled(true);
                    refreshBtn.setText("Refresh Game List");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Failed to fetch games:\n" + ex.getMessage(),
                            "RMI Error", JOptionPane.ERROR_MESSAGE);
                    refreshBtn.setEnabled(true);
                    refreshBtn.setText("Refresh Game List");
                });
            }
        }).start();
    }

    // Creates a room without blocking the EDT.
    private void handleCreateGame(ActionEvent e) {
        String gameName = newGameField.getText().trim();
        if (gameName.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Room name cannot be empty.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        createGameBtn.setEnabled(false);
        joinGameBtn.setEnabled(false);

        new Thread(() -> {
            try {
                myMark = 'X';
                controller.createGame(gameName);
                SwingUtilities.invokeLater(() -> {
                    setupGameUIForWait(gameName);
                    cardLayout.show(mainPanel, "GAME");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Failed to create game:\n" + ex.getMessage(), "Error", JOptionPane.WARNING_MESSAGE);
                    createGameBtn.setEnabled(true);
                    joinGameBtn.setEnabled(true);
                });
            }
        }).start();
    }

    // Joins the selected room without blocking the EDT.
    private void handleJoinGame(ActionEvent e) {
        String gameName = waitingGamesList.getSelectedValue();
        if (gameName == null) {
            JOptionPane.showMessageDialog(this, "Please select a game from the list to join.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        createGameBtn.setEnabled(false);
        joinGameBtn.setEnabled(false);

        new Thread(() -> {
            try {
                myMark = 'O';
                controller.joinGame(gameName);
                SwingUtilities.invokeLater(() -> {
                    setupGameUIForWait(gameName);
                    cardLayout.show(mainPanel, "GAME");
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Failed to join game:\n" + ex.getMessage(), "Error", JOptionPane.WARNING_MESSAGE);
                    createGameBtn.setEnabled(true);
                    joinGameBtn.setEnabled(true);
                });
            }
        }).start();
    }

    // Leaves the current room and returns to the lobby.
    private void handleLeaveGame(ActionEvent e) {
        new Thread(() -> {
            try {
                controller.leaveGame();
            } catch (Exception ex) {
                // Ignore
            } finally {
                SwingUtilities.invokeLater(() -> {
                    myMark = ' ';
                    createGameBtn.setEnabled(true);
                    joinGameBtn.setEnabled(true);
                    cardLayout.show(mainPanel, "LOBBY");
                    refreshWaitingGames();
                });
            }
        }).start();
    }

    // Sends one move to the server.
    private void handleBoardClick(int r, int c) {
        // Prevent double clicks while the remote request is in flight.
        setBoardEnabled(false);
        
        new Thread(() -> {
            try {
                controller.makeMove(r, c);
            } catch (NotYourTurnException ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "It's not your turn!", "Move Rejected", JOptionPane.WARNING_MESSAGE));
            } catch (InvalidMoveException ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Invalid Move:\n" + ex.getMessage(), "Move Rejected", JOptionPane.WARNING_MESSAGE));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Error during move submission:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
    }

    // --- State & UI Updates ---

    // Resets the game screen while waiting for the opponent.
    private void setupGameUIForWait(String gameName) {
        gameStatusLbl.setText("Waiting for opponent...");
        gameOpponentLbl.setText("Room: " + gameName + " | You are: " + myMark);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                boardButtons[r][c].setText("");
                boardButtons[r][c].setEnabled(false);
            }
        }
    }

    // Redraws the board and updates labels.
    private void updateBoardState(BoardState state) {
        // Redraw every board cell from the latest snapshot.
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                char mark = state.getMark(r, c);
                JButton btn = boardButtons[r][c];
                btn.setText(mark == ' ' ? "" : String.valueOf(mark));
                if (mark == 'X') {
                    btn.setForeground(COLOR_X);
                } else if (mark == 'O') {
                    btn.setForeground(COLOR_O);
                }
            }
        }

        GameStatus status = state.status();
        if (status == GameStatus.ACTIVE) {
            // Update labels and enable the board only on the local turn.
            String opponentName = myMark == 'X' ? state.playerO() : state.playerX();
            gameOpponentLbl.setText("Opponent: " + opponentName + " (" + (myMark == 'X' ? 'O' : 'X') + ") | You: " + controller.getPlayerName() + " (" + myMark + ")");

            if (state.turnOf().equals(controller.getPlayerName())) {
                gameStatusLbl.setText("Your turn!");
                gameStatusLbl.setForeground(COLOR_STATUS);
                setBoardEnabled(true);
            } else {
                gameStatusLbl.setText("Opponent's turn...");
                gameStatusLbl.setForeground(COLOR_WARN);
                setBoardEnabled(false);
            }
        } else {
            // Freeze the board and show the final result.
            setBoardEnabled(false);
            showEndGameDialog(state);
        }
    }

    // Enables only the cells that can still be played.
    private void setBoardEnabled(boolean enabled) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                JButton btn = boardButtons[r][c];
                if (!btn.getText().isEmpty()) {
                    // Keep occupied cells visually strong.
                    btn.setEnabled(true);
                } else {
                    // Empty cells depend on the local turn.
                    btn.setEnabled(enabled);
                }
            }
        }
    }

    // Shows the final outcome and resets the UI.
    private void showEndGameDialog(BoardState state) {
        GameStatus status = state.status();
        String message;
        String title = "Game Over";

        if (status == GameStatus.DRAW) {
            message = "The match ended in a Draw!";
            gameStatusLbl.setText("Result: DRAW");
            gameStatusLbl.setForeground(COLOR_FG);
        } else if (status == GameStatus.ABANDONED) {
            message = "The game was abandoned. Opponent left or disconnected!";
            gameStatusLbl.setText("Result: ABANDONED");
            gameStatusLbl.setForeground(COLOR_X);
        } else {
            String winner = status == GameStatus.WON_X ? state.playerX() : state.playerO();
            if (winner.equals(controller.getPlayerName())) {
                message = "Congratulations, you won!";
                gameStatusLbl.setText("Result: YOU WON!");
                gameStatusLbl.setForeground(COLOR_STATUS);
            } else {
                message = "You lost. Winner: " + winner;
                gameStatusLbl.setText("Result: YOU LOST");
                gameStatusLbl.setForeground(COLOR_X);
            }
        }

        JOptionPane.showMessageDialog(this, message, title, JOptionPane.INFORMATION_MESSAGE);

        // Reset local state and return to the lobby.
        myMark = ' ';
        createGameBtn.setEnabled(true);
        joinGameBtn.setEnabled(true);
        cardLayout.show(mainPanel, "LOBBY");
        refreshWaitingGames();
    }

    // Applies the start event on the EDT.
    @Override
    public void onGameStarted(BoardState initialState) {
        SwingUtilities.invokeLater(() -> updateBoardState(initialState));
    }

    // Applies a board update on the EDT.
    @Override
    public void onGameUpdated(BoardState newState) {
        SwingUtilities.invokeLater(() -> updateBoardState(newState));
    }

    // Shows the opponent-left notification on the EDT.
    @Override
    public void onOpponentLeft(String opponentName) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this,
                "Opponent '" + opponentName + "' has left the match.",
                "Match Abandoned", JOptionPane.WARNING_MESSAGE
        ));
    }
}
