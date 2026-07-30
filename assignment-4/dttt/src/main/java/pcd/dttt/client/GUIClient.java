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

/**
 * Swing Graphical User Interface client for Distributed Tic-Tac-Toe.
 * Decoupled from direct RMI network references by communicating via the {@link GameController} abstraction.
 * Implements {@link GameEventListener} to receive state notifications and safely updates components on the EDT.
 */
public class GUIClient extends JFrame implements GameEventListener {
    @Serial
    private static final long serialVersionUID = 1L;

    // Sleek Dark Theme Color Palette
    /** Background color for the frame and panels. */
    private static final Color COLOR_BG = new Color(30, 30, 46);
    /** Background color for secondary panels. */
    private static final Color COLOR_PANEL_BG = new Color(49, 50, 68);
    /** Default button background color. */
    private static final Color COLOR_BTN_BG = new Color(17, 17, 27);
    /** Foreground color for default text labels. */
    private static final Color COLOR_FG = new Color(205, 214, 244);
    /** Status color representing success or positive state. */
    private static final Color COLOR_STATUS = new Color(166, 227, 161);
    /** Warning color representing pending or waiting state. */
    private static final Color COLOR_WARN = new Color(249, 226, 175);

    // High-Contrast Button Colors (harmonized with Catppuccin Mocha dark theme)
    /** Primary button color (e.g. Connection setup actions). */
    private static final Color COLOR_BTN_PRIMARY = new Color(45, 79, 124);
    /** Success button color (e.g. Join actions). */
    private static final Color COLOR_BTN_SUCCESS = new Color(45, 94, 64);
    /** Danger button color (e.g. Leave match actions). */
    private static final Color COLOR_BTN_DANGER = new Color(124, 53, 67);
    /** Secondary action button color (e.g. Refresh lobby list). */
    private static final Color COLOR_BTN_SECONDARY = new Color(69, 71, 90);

    /** Color representation for 'X' mark. */
    private static final Color COLOR_X = new Color(255, 85, 120);
    /** Color representation for 'O' mark. */
    private static final Color COLOR_O = new Color(85, 170, 255);

    /** Card layout containing the application screens. */
    private final CardLayout cardLayout;
    /** The main wrapper panel holding all cards. */
    private final JPanel mainPanel;

    // Game Client Controller & State
    /** The controller abstraction decoupling the UI from the network. */
    private final GameController controller;
    /** Default host used to prefill the connection form. */
    private final String defaultHost;
    /** Default port used to prefill the connection form. */
    private final int defaultPort;
    /** Default service name used to prefill the connection form. */
    private final String defaultServiceName;
    /** The player's assigned game mark ('X' or 'O'). Defaults to empty space. */
    private char myMark = ' '; // 'X' or 'O' or ' '

    // Connection Screen Components
    /** Input field for server host address. */
    private JTextField hostField;
    /** Input field for server port number. */
    private JTextField portField;
    /** Input field for the lobby service binding name. */
    private JTextField serviceField;
    /** Input field for player nickname. */
    private JTextField nameField;
    /** Button to initiate connection to the server. */
    private JButton connectBtn;

    // Lobby Screen Components
    /** Input field for new game name creation. */
    private JTextField newGameField;
    /** List component for displaying active game sessions. */
    private JList<String> waitingGamesList;
    /** Model for game sessions list. */
    private DefaultListModel<String> listModel;
    /** Button to create a new game session. */
    private JButton createGameBtn;
    /** Button to join an existing session. */
    private JButton joinGameBtn;
    /** Button to refresh the list of available games. */
    private JButton refreshBtn;
    /** Label for displaying lobby-related status messages. */
    private JLabel lobbyStatusLbl;

    // Game Screen Components
    /** 3x3 matrix of board buttons. */
    private JButton[][] boardButtons;
    /** Label to show turn or game outcome status. */
    private JLabel gameStatusLbl;
    /** Label to display opponent info. */
    private JLabel gameOpponentLbl;

    /**
     * Constructs a new GUI client frame.
     *
     * @param controller the game controller interface
     * @param defaultHost the host used to prefill the connection form
     * @param defaultPort the port used to prefill the connection form
     * @param defaultServiceName the service name used to prefill the connection form
     */
    public GUIClient(GameController controller, String defaultHost, int defaultPort, String defaultServiceName) {
        super("Distributed Tic-Tac-Toe");
        this.controller = controller;
        this.defaultHost = defaultHost;
        this.defaultPort = defaultPort;
        this.defaultServiceName = defaultServiceName;
        this.controller.registerEventListener(this);
        
        // Window Setup
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(550, 650);
        setLocationRelativeTo(null);

        // Window Closing Listener to disconnect cleanly
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                cleanupAndExit();
            }
        });

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);
        mainPanel.setBackground(COLOR_BG);

        // Build screens
        mainPanel.add(buildConnectionScreen(), "CONNECTION");
        mainPanel.add(buildLobbyScreen(), "LOBBY");
        mainPanel.add(buildGameScreen(), "GAME");

        add(mainPanel);
        cardLayout.show(mainPanel, "CONNECTION");
    }

    /**
     * Cleans up RMI client stubs and exits the application.
     */
    private void cleanupAndExit() {
        new Thread(() -> {
            controller.disconnect();
            System.exit(0);
        }).start();
    }

    // --- Screen Builders ---

    /**
     * Builds the connection screen UI.
     * @return Panel configured for connection inputs.
     */
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

    /**
     * Builds the lobby screen UI.
     * @return Panel configured for lobby interaction.
     */
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

    /**
     * Builds the main game screen UI.
     * @return Panel configured for the game board.
     */
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
        /* Panel container for the tic-tac-toe grid. */
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
        /* Button to leave the current match. */
        JButton leaveGameBtn = createStyledButton("Leave Match", COLOR_BTN_DANGER);
        leaveGameBtn.addActionListener(this::handleLeaveGame);
        panel.add(leaveGameBtn, BorderLayout.SOUTH);

        return panel;
    }

    // --- Helper UI Factory Methods ---

    /**
     * Factory method for creating a main screen container panel with padding.
     * @return formatted border layout panel
     */
    private JPanel createScreenPanel() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(COLOR_BG);
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));
        return panel;
    }

    /**
     * Factory method for creating a top header sub-panel with vertical grid layout.
     * @return formatted header panel
     */
    private JPanel createHeaderPanel() {
        JPanel panel = new JPanel(new GridLayout(2, 1, 5, 5));
        panel.setBackground(COLOR_BG);
        return panel;
    }

    /**
     * Factory method for creating a grid cell button on the game board with click handlers and hover effects.
     *
     * @param row grid row index
     * @param col grid column index
     * @return initialized grid cell button
     */
    private JButton createBoardCellButton(int row, int col) {
        JButton btn = new JButton("");
        btn.setBackground(COLOR_BTN_BG);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font("SansSerif", Font.BOLD, 54));
        btn.setFocusPainted(false);
        btn.setBorder(new LineBorder(new Color(69, 71, 90), 2));
        btn.addActionListener(e -> handleBoardClick(row, col));
        btn.setEnabled(false); // Disabled until match starts

        // Highlight Empty Cell Hover Effects
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

    /**
     * Factory method for styled labels.
     * @param text label text
     * @return formatted label
     */
    private JLabel createLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 15));
        lbl.setForeground(Color.WHITE);
        return lbl;
    }

    /**
     * Factory method for styled text fields.
     * @param text default text
     * @return styled text field
     */
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

    /**
     * Factory method for styled buttons.
     * @param text button label
     * @param baseColor base background color
     * @return styled button
     */
    private JButton createStyledButton(String text, Color baseColor) {
        JButton btn = new JButton(text);
        btn.setBackground(baseColor);
        btn.setForeground(Color.WHITE); // Force crisp white text for maximum readability
        
        btn.setFocusPainted(false);
        btn.setFont(new Font("SansSerif", Font.BOLD, 15));
        btn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(baseColor.darker(), 1, true),
                BorderFactory.createEmptyBorder(8, 15, 8, 15)
        ));
        
        // Compute high-contrast hover color
        Color hoverColor = getHoverColor(baseColor);
        
        // Subtle hover effects
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

    /**
     * Calculates a darkened hover color for buttons.
     * @param color base color
     * @return hover color
     */
    private Color getHoverColor(Color color) {
        // Blend 85% original color and 15% black for a clean, high-contrast dark hover state
        int r = (int)(color.getRed() * 0.85);
        int g = (int)(color.getGreen() * 0.85);
        int b = (int)(color.getBlue() * 0.85);
        return new Color(r, g, b);
    }

    // --- Event Handlers ---

    /**
     * Action handler to establish RMI server connections.
     * Launches matchmaking connection asynchronously on a worker thread to prevent EDT lockup.
     *
     * @param e the action event
     */
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

        connectBtn.setEnabled(false);
        connectBtn.setText("Connecting...");

        new Thread(() -> {
            try {
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

    /**
     * Queries the controller for waiting rooms and updates the Swing list model asynchronously.
     */
    private void refreshWaitingGames() {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("Refreshing...");
        new Thread(() -> {
            try {
                List<String> gamesList = controller.getWaitingGames();
                SwingUtilities.invokeLater(() -> {
                    listModel.clear();
                    for (String g : gamesList) {
                        listModel.addElement(g);
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

    /**
     * Action handler to create a new game room.
     * Requests game creation via a worker thread and transitions screen state.
     *
     * @param e the action event
     */
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
                myMark = 'X'; // Creator is X
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

    /**
     * Action handler to join the selected waiting room.
     * Initiates joining flow on a worker thread.
     *
     * @param e the action event
     */
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
                myMark = 'O'; // Joiner is O
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

    /**
     * Action handler to leave the current active game.
     * Signals leaving via a worker thread and transitions view back to the Lobby.
     *
     * @param e the action event
     */
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

    /**
     * Action handler triggered when clicking a grid cell.
     * Submits the move coordinates asynchronously and updates UI status.
     *
     * @param r the grid row index
     * @param c the grid column index
     */
    private void handleBoardClick(int r, int c) {
        // Temporarily disable buttons to prevent double clicks during RMI roundtrip
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

    /**
     * Prepares and resets the game screen components while waiting for an opponent.
     *
     * @param gameName the name of the game room
     */
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

    /**
     * Redraws the 3x3 board grid buttons and updates labels based on the new BoardState.
     *
     * @param state the updated board state
     */
    private void updateBoardState(BoardState state) {
        // Draw grid
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
            // Terminal states
            setBoardEnabled(false);
            showEndGameDialog(state);
        }
    }

    /**
     * Configures the grid buttons' enabled/disabled states.
     * Keeps occupied cells enabled to ensure color highlights remain high-contrast.
     *
     * @param enabled true if it is the local player's turn, false otherwise
     */
    private void setBoardEnabled(boolean enabled) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                JButton btn = boardButtons[r][c];
                if (!btn.getText().isEmpty()) {
                    // Always keep occupied cells enabled so the 'X' and 'O' markings
                    // remain in high-contrast pink/blue rather than disabled gray!
                    btn.setEnabled(true);
                } else {
                    // Empty cells are only enabled when it's our turn
                    btn.setEnabled(enabled);
                }
            }
        }
    }

    /**
     * Displays a dialog detailing the game outcome and resets components.
     *
     * @param state the final terminal board state
     */
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

        // Reset and go back to Lobby
        myMark = ' ';
        createGameBtn.setEnabled(true);
        joinGameBtn.setEnabled(true);
        cardLayout.show(mainPanel, "LOBBY");
        refreshWaitingGames();
    }

    // --- GameEventListener RMI Callbacks (Thread Safety handled via SwingUtilities.invokeLater) ---

    /**
     * {@inheritDoc}
     *
     * @param initialState the initial board state snapshot when the game starts
     */
    @Override
    public void onGameStarted(BoardState initialState) {
        SwingUtilities.invokeLater(() -> updateBoardState(initialState));
    }

    /**
     * {@inheritDoc}
     *
     * @param newState the updated board state snapshot
     */
    @Override
    public void onGameUpdated(BoardState newState) {
        SwingUtilities.invokeLater(() -> updateBoardState(newState));
    }

    /**
     * {@inheritDoc}
     *
     * @param opponentName the nickname of the opponent who left
     */
    @Override
    public void onOpponentLeft(String opponentName) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this,
                "Opponent '" + opponentName + "' has left the match.",
                "Match Abandoned", JOptionPane.WARNING_MESSAGE
        ));
    }
}
