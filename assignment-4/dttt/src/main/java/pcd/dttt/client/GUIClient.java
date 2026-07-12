package pcd.dttt.client;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.Game;
import pcd.dttt.common.GameStatus;
import pcd.dttt.common.Lobby;
import pcd.dttt.common.exceptions.GameAlreadyExistsException;
import pcd.dttt.common.exceptions.GameFullException;
import pcd.dttt.common.exceptions.GameNotFoundException;
import pcd.dttt.common.exceptions.InvalidMoveException;
import pcd.dttt.common.exceptions.NotYourTurnException;

/**
 * Graphical User Interface client for Distributed Tic-Tac-Toe.
 * Implements GameEventListener to handle RMI callbacks.
 */
public class GUIClient extends JFrame implements GameEventListener {
    private static final long serialVersionUID = 1L;

    // Sleek Dark Theme Color Palette
    private static final Color COLOR_BG = new Color(30, 30, 46);         // Dark slate background
    private static final Color COLOR_PANEL_BG = new Color(49, 50, 68);   // Slightly lighter panel bg
    private static final Color COLOR_BTN_BG = new Color(17, 17, 27);      // Deep button background
    private static final Color COLOR_FG = new Color(205, 214, 244);      // Off-white text
    private static final Color COLOR_ACCENT = new Color(137, 180, 250);  // Pastel blue
    private static final Color COLOR_X = new Color(243, 139, 168);       // Pastel pink/red for X
    private static final Color COLOR_O = new Color(137, 180, 250);       // Pastel blue for O
    private static final Color COLOR_STATUS = new Color(166, 227, 161);  // Pastel green for status
    private static final Color COLOR_WARN = new Color(249, 226, 175);    // Pastel yellow for warnings

    private final CardLayout cardLayout;
    private final JPanel mainPanel;

    // RMI & Client State
    private Lobby lobby;
    private Game currentGame;
    private PlayerClientImpl clientStub;
    private String playerName;
    private char myMark = ' '; // 'X' or 'O' or ' '

    // Connection Screen Components
    private JTextField hostField;
    private JTextField portField;
    private JTextField nameField;
    private JButton connectBtn;

    // Lobby Screen Components
    private JTextField newGameField;
    private JList<String> waitingGamesList;
    private DefaultListModel<String> listModel;
    private JButton createGameBtn;
    private JButton joinGameBtn;
    private JButton refreshBtn;
    private JLabel lobbyStatusLbl;

    // Game Screen Components
    private JButton[][] boardButtons;
    private JLabel gameStatusLbl;
    private JLabel gameOpponentLbl;
    private JButton leaveGameBtn;
    private JPanel boardPanel;

    public GUIClient() {
        super("Distributed Tic-Tac-Toe");
        
        // Window Setup
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(550, 600);
        setLocationRelativeTo(null);

        // Export Client RMI Stub
        try {
            this.clientStub = new PlayerClientImpl(this);
        } catch (RemoteException e) {
            JOptionPane.showMessageDialog(this, "Failed to initialize RMI Client Stub:\n" + e.getMessage(),
                    "RMI Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }

        // Window Closing Listener to leave current game cleanly
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

    private void cleanupAndExit() {
        if (currentGame != null) {
            try {
                // Non-blocking call or in background to not freeze UI shutdown
                new Thread(() -> {
                    try {
                        currentGame.leaveGame(playerName);
                    } catch (Exception e) {
                        // Ignore
                    }
                    unexportAndExit();
                }).start();
                return;
            } catch (Exception e) {
                // Ignore
            }
        }
        unexportAndExit();
    }

    private void unexportAndExit() {
        try {
            java.rmi.server.UnicastRemoteObject.unexportObject(clientStub, true);
        } catch (Exception e) {
            // Ignore
        }
        System.exit(0);
    }

    // --- Screen Builders ---

    private JPanel buildConnectionScreen() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(COLOR_BG);
        panel.setBorder(new EmptyBorder(30, 30, 30, 30));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(10, 10, 10, 10);

        JLabel title = new JLabel("Distributed Tic-Tac-Toe", JLabel.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 28));
        title.setForeground(COLOR_FG);
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

        hostField = createTextField("localhost");
        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 0.7;
        panel.add(hostField, gbc);

        JLabel portLabel = createLabel("Server Port:");
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0.3;
        panel.add(portLabel, gbc);

        portField = createTextField("1099");
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.weightx = 0.7;
        panel.add(portField, gbc);

        JLabel nameLabel = createLabel("Your Name:");
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0.3;
        panel.add(nameLabel, gbc);

        nameField = createTextField("");
        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.weightx = 0.7;
        panel.add(nameField, gbc);

        connectBtn = createStyledButton("Connect to Lobby", COLOR_ACCENT);
        connectBtn.addActionListener(this::handleConnect);
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(20, 10, 10, 10);
        panel.add(connectBtn, gbc);

        return panel;
    }

    private JPanel buildLobbyScreen() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(COLOR_BG);
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));

        // Top Panel - Header
        JPanel topPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        topPanel.setBackground(COLOR_BG);
        JLabel title = new JLabel("Game Lobby", JLabel.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 22));
        title.setForeground(COLOR_FG);
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
                new LineBorder(COLOR_ACCENT, 1, true), "Available Games", 
                0, 0, new Font("SansSerif", Font.BOLD, 14), COLOR_FG
        ));
        
        listModel = new DefaultListModel<>();
        waitingGamesList = new JList<>(listModel);
        waitingGamesList.setBackground(COLOR_BTN_BG);
        waitingGamesList.setForeground(COLOR_FG);
        waitingGamesList.setFont(new Font("SansSerif", Font.PLAIN, 15));
        waitingGamesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(waitingGamesList);
        scrollPane.setBorder(null);
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        // Refresh Button
        refreshBtn = createStyledButton("Refresh Game List", COLOR_PANEL_BG);
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

        createGameBtn = createStyledButton("Create Game", COLOR_ACCENT);
        createGameBtn.addActionListener(this::handleCreateGame);
        gbc.gridy = 2;
        eastPanel.add(createGameBtn, gbc);

        // Separator space
        gbc.gridy = 3;
        gbc.insets = new Insets(20, 5, 20, 5);
        eastPanel.add(new JSeparator(JSeparator.HORIZONTAL), gbc);
        gbc.insets = new Insets(10, 5, 10, 5);

        // Join Section
        joinGameBtn = createStyledButton("Join Selected", COLOR_STATUS);
        joinGameBtn.addActionListener(this::handleJoinGame);
        gbc.gridy = 4;
        eastPanel.add(joinGameBtn, gbc);

        panel.add(eastPanel, BorderLayout.EAST);

        return panel;
    }

    private JPanel buildGameScreen() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(COLOR_BG);
        panel.setBorder(new EmptyBorder(20, 20, 20, 20));

        // Top Information
        JPanel infoPanel = new JPanel(new GridLayout(2, 1, 5, 5));
        infoPanel.setBackground(COLOR_BG);
        
        gameStatusLbl = new JLabel("Waiting for match to begin...", JLabel.CENTER);
        gameStatusLbl.setFont(new Font("SansSerif", Font.BOLD, 18));
        gameStatusLbl.setForeground(COLOR_STATUS);

        gameOpponentLbl = new JLabel("Opponent: -", JLabel.CENTER);
        gameOpponentLbl.setFont(new Font("SansSerif", Font.PLAIN, 14));
        gameOpponentLbl.setForeground(COLOR_FG);

        infoPanel.add(gameStatusLbl);
        infoPanel.add(gameOpponentLbl);
        panel.add(infoPanel, BorderLayout.NORTH);

        // Center Grid 3x3
        boardPanel = new JPanel(new GridLayout(3, 3, 10, 10));
        boardPanel.setBackground(COLOR_PANEL_BG);
        boardPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        boardButtons = new JButton[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                final int row = r;
                final int col = c;
                JButton btn = new JButton("");
                btn.setBackground(COLOR_BTN_BG);
                btn.setForeground(COLOR_FG);
                btn.setFont(new Font("SansSerif", Font.BOLD, 54));
                btn.setFocusPainted(false);
                btn.setBorder(new LineBorder(COLOR_BG, 2));
                btn.addActionListener(e -> handleBoardClick(row, col));
                btn.setEnabled(false); // Disabled until match starts
                boardButtons[r][c] = btn;
                boardPanel.add(btn);
            }
        }
        panel.add(boardPanel, BorderLayout.CENTER);

        // Bottom - Leave button
        leaveGameBtn = createStyledButton("Leave Match", COLOR_X);
        leaveGameBtn.addActionListener(this::handleLeaveGame);
        panel.add(leaveGameBtn, BorderLayout.SOUTH);

        return panel;
    }

    // --- Helper UI Factory Methods ---

    private JLabel createLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font("SansSerif", Font.PLAIN, 15));
        lbl.setForeground(COLOR_FG);
        return lbl;
    }

    private JTextField createTextField(String text) {
        JTextField tf = new JTextField(text);
        tf.setBackground(COLOR_BTN_BG);
        tf.setForeground(COLOR_FG);
        tf.setCaretColor(COLOR_FG);
        tf.setFont(new Font("SansSerif", Font.PLAIN, 15));
        tf.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(COLOR_PANEL_BG, 1, true),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        return tf;
    }

    private JButton createStyledButton(String text, Color baseColor) {
        JButton btn = new JButton(text);
        btn.setBackground(baseColor);
        btn.setForeground(COLOR_FG);
        btn.setFocusPainted(false);
        btn.setFont(new Font("SansSerif", Font.BOLD, 15));
        btn.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(baseColor.darker(), 1, true),
                BorderFactory.createEmptyBorder(8, 15, 8, 15)
        ));
        
        // Subtle hover effects
        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                btn.setBackground(baseColor.brighter());
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent evt) {
                btn.setBackground(baseColor);
            }
        });
        return btn;
    }

    // --- Event Handlers ---

    private void handleConnect(ActionEvent e) {
        String host = hostField.getText().trim();
        String portStr = portField.getText().trim();
        playerName = nameField.getText().trim();

        if (host.isEmpty() || portStr.isEmpty() || playerName.isEmpty()) {
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

        // Perform connection in a separate thread so as not to freeze the EDT
        new Thread(() -> {
            try {
                Registry registry = LocateRegistry.getRegistry(host, port);
                lobby = (Lobby) registry.lookup("Lobby");

                SwingUtilities.invokeLater(() -> {
                    lobbyStatusLbl.setText("Connected as: " + playerName);
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

    private void refreshWaitingGames() {
        refreshBtn.setEnabled(false);
        refreshBtn.setText("Refreshing...");
        new Thread(() -> {
            try {
                List<String> gamesList = lobby.getWaitingGames();
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
                Game game = lobby.createGame(gameName, playerName, clientStub);
                SwingUtilities.invokeLater(() -> {
                    currentGame = game;
                    setupGameUIForWait(gameName);
                    cardLayout.show(mainPanel, "GAME");
                });
            } catch (GameAlreadyExistsException ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Error", JOptionPane.WARNING_MESSAGE);
                    createGameBtn.setEnabled(true);
                    joinGameBtn.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Failed to create game:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    createGameBtn.setEnabled(true);
                    joinGameBtn.setEnabled(true);
                });
            }
        }).start();
    }

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
                Game game = lobby.joinGame(gameName, playerName, clientStub);
                SwingUtilities.invokeLater(() -> {
                    currentGame = game;
                    setupGameUIForWait(gameName);
                    cardLayout.show(mainPanel, "GAME");
                });
            } catch (GameNotFoundException | GameFullException ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Error", JOptionPane.WARNING_MESSAGE);
                    createGameBtn.setEnabled(true);
                    joinGameBtn.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Failed to join game:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    createGameBtn.setEnabled(true);
                    joinGameBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private void handleLeaveGame(ActionEvent e) {
        new Thread(() -> {
            try {
                if (currentGame != null) {
                    currentGame.leaveGame(playerName);
                }
            } catch (Exception ex) {
                // Ignore
            } finally {
                SwingUtilities.invokeLater(() -> {
                    currentGame = null;
                    myMark = ' ';
                    createGameBtn.setEnabled(true);
                    joinGameBtn.setEnabled(true);
                    cardLayout.show(mainPanel, "LOBBY");
                    refreshWaitingGames();
                });
            }
        }).start();
    }

    private void handleBoardClick(int r, int c) {
        // Temporarily disable buttons to prevent double clicks during RMI roundtrip
        setBoardEnabled(false);
        
        new Thread(() -> {
            try {
                currentGame.makeMove(playerName, r, c);
            } catch (NotYourTurnException ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "It's not your turn!", "Move Rejected", JOptionPane.WARNING_MESSAGE);
                });
            } catch (InvalidMoveException ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Invalid Move:\n" + ex.getMessage(), "Move Rejected", JOptionPane.WARNING_MESSAGE);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "RMI Error during move:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    // --- State & UI Updates ---

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

        GameStatus status = state.getStatus();
        if (status == GameStatus.ACTIVE) {
            String opponentName = myMark == 'X' ? state.getPlayerO() : state.getPlayerX();
            gameOpponentLbl.setText("Opponent: " + opponentName + " (" + (myMark == 'X' ? 'O' : 'X') + ") | You: " + playerName + " (" + myMark + ")");

            if (state.getTurnOf().equals(playerName)) {
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

    private void setBoardEnabled(boolean enabled) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                // Only enable cells that are empty
                if (enabled) {
                    boardButtons[r][c].setEnabled(boardButtons[r][c].getText().isEmpty());
                } else {
                    boardButtons[r][c].setEnabled(false);
                }
            }
        }
    }

    private void showEndGameDialog(BoardState state) {
        GameStatus status = state.getStatus();
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
            String winner = status == GameStatus.WON_X ? state.getPlayerX() : state.getPlayerO();
            if (winner.equals(playerName)) {
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
        currentGame = null;
        myMark = ' ';
        createGameBtn.setEnabled(true);
        joinGameBtn.setEnabled(true);
        cardLayout.show(mainPanel, "LOBBY");
        refreshWaitingGames();
    }

    // --- GameEventListener RMI Callbacks (Thread Safety handled via SwingUtilities.invokeLater) ---

    @Override
    public void onGameStarted(BoardState initialState) {
        SwingUtilities.invokeLater(() -> {
            updateBoardState(initialState);
        });
    }

    @Override
    public void onGameUpdated(BoardState newState) {
        SwingUtilities.invokeLater(() -> {
            updateBoardState(newState);
        });
    }

    @Override
    public void onOpponentLeft(String opponentName) {
        SwingUtilities.invokeLater(() -> {
            // Can show a warning dialog or status message
            JOptionPane.showMessageDialog(this, "Opponent '" + opponentName + "' has left the match.",
                    "Match Abandoned", JOptionPane.WARNING_MESSAGE);
        });
    }
}
