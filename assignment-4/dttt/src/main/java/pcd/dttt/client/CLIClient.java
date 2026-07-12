package pcd.dttt.client;

import java.util.List;
import java.util.Scanner;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.GameStatus;
import pcd.dttt.common.exceptions.InvalidMoveException;
import pcd.dttt.common.exceptions.NotYourTurnException;

/**
 * Command Line Interface client for the Distributed Tic-Tac-Toe game.
 * Communicates with the server strictly via the {@link GameController} abstraction.
 */
public class CLIClient implements GameEventListener {
    /** The controller abstraction decoupling CLI from RMI. */
    private final GameController controller;

    /** The remote server host IP/name. */
    private final String host;

    /** The remote server RMI port. */
    private final int port;

    /** The scanner for user command-line input. */
    private final Scanner scanner;

    /** Lock for synchronizing game-related RMI callback events. */
    private final Object gameLock = new Object();

    /** Nickname of the local player. */
    private String playerName;

    /** Current board state snapshot. */
    private BoardState currentBoardState;

    /** Flag indicating if the opponent left or disconnected. */
    private boolean opponentLeftFlag = false;

    /**
     * Constructs a new CLI client.
     *
     * @param controller the game controller abstraction
     * @param host the remote server hostname
     * @param port the remote server RMI port
     */
    public CLIClient(GameController controller, String host, int port) {
        this.controller = controller;
        this.host = host;
        this.port = port;
        this.scanner = new Scanner(System.in);
    }

    /**
     * Launches the CLI client setup and main execution loops.
     */
    public void start() {
        System.out.println("=== Welcome to Distributed Tic-Tac-Toe ===");
        
        // Get player name
        while (playerName == null || playerName.isBlank()) {
            System.out.print("Enter your name: ");
            playerName = scanner.nextLine().trim();
            if (playerName.isBlank()) {
                System.out.println("Name cannot be empty!");
            }
        }

        // Establish connection via controller
        try {
            System.out.println("Connecting to lobby...");
            controller.connect(host, port, playerName);
            controller.registerEventListener(this);
        } catch (Exception e) {
            System.err.println("Connection failed: " + e.getMessage());
            return;
        }

        boolean exit = false;
        while (!exit) {
            showMainMenu();
            System.out.print("Choose an option: ");
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    createNewGame();
                    break;
                case "2":
                    joinExistingGame();
                    break;
                case "3":
                    listWaitingGames();
                    break;
                case "4":
                    exit = true;
                    System.out.println("Goodbye!");
                    break;
                default:
                    System.out.println("Invalid option. Please choose between 1 and 4.");
            }
        }
        
        controller.disconnect();
    }

    /**
     * Prints the primary user actions to standard output.
     */
    private void showMainMenu() {
        System.out.println("\n--- Main Menu ---");
        System.out.println("1. Create a new game");
        System.out.println("2. Join an existing game");
        System.out.println("3. List games waiting for players");
        System.out.println("4. Exit");
    }

    /**
     * Prompts the user for a game name, requests the controller to create a room,
     * and runs the game loop once the room is created.
     */
    private void createNewGame() {
        System.out.print("Enter game name: ");
        String gameName = scanner.nextLine().trim();
        if (gameName.isBlank()) {
            System.out.println("Game name cannot be empty.");
            return;
        }

        try {
            synchronized (gameLock) {
                currentBoardState = null;
                opponentLeftFlag = false;
            }
            
            controller.createGame(gameName);
            System.out.println("Game '" + gameName + "' created successfully.");
            playGameLoop();

        } catch (Exception e) {
            System.out.println("Error creating game: " + e.getMessage());
        }
    }

    /**
     * Prompts the user for a game name, requests the controller to join it,
     * and transitions to the game loop.
     */
    private void joinExistingGame() {
        System.out.print("Enter game name to join: ");
        String gameName = scanner.nextLine().trim();
        if (gameName.isBlank()) {
            System.out.println("Game name cannot be empty.");
            return;
        }

        try {
            synchronized (gameLock) {
                currentBoardState = null;
                opponentLeftFlag = false;
            }

            controller.joinGame(gameName);
            System.out.println("Joined game '" + gameName + "' successfully.");
            playGameLoop();

        } catch (Exception e) {
            System.out.println("Error joining game: " + e.getMessage());
        }
    }

    /**
     * Requests the list of waiting games from the controller and prints it.
     */
    private void listWaitingGames() {
        try {
            List<String> waiting = controller.getWaitingGames();
            if (waiting.isEmpty()) {
                System.out.println("No games are currently waiting for players.");
            } else {
                System.out.println("Games waiting for players:");
                for (String gameName : waiting) {
                    System.out.println("- " + gameName);
                }
            }
        } catch (Exception e) {
            System.out.println("Error fetching game list: " + e.getMessage());
        }
    }

    /**
     * Primary gameplay execution loop. Handles drawing the board, prompting for moves,
     * communicating moves via the controller, and waiting on turn notifications.
     */
    private void playGameLoop() {
        // Wait for the game to start if waiting
        synchronized (gameLock) {
            while (currentBoardState == null || currentBoardState.getStatus() == GameStatus.WAITING) {
                System.out.println("Waiting for an opponent to join...");
                try {
                    gameLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        System.out.println("\n=================================");
        System.out.println("       THE MATCH HAS STARTED     ");
        System.out.println("=================================");

        while (true) {
            BoardState state;
            boolean leftFlag;
            synchronized (gameLock) {
                state = currentBoardState;
                leftFlag = opponentLeftFlag;
            }

            if (state == null) {
                break;
            }

            if (state.getStatus() != GameStatus.ACTIVE) {
                System.out.println("\nFinal Board:");
                printBoard(state);
                showGameEndResult(state, leftFlag);
                break;
            }

            printBoard(state);

            if (state.getTurnOf().equals(playerName)) {
                System.out.println("It's your turn!");
                System.out.print("Enter coordinates (row col, e.g. '0 1') or 'leave': ");
                String input = scanner.nextLine().trim();
                
                if (input.equalsIgnoreCase("leave")) {
                    try {
                        controller.leaveGame();
                    } catch (Exception e) {
                        System.out.println("Error leaving game: " + e.getMessage());
                    }
                    break;
                }

                String[] tokens = input.split("\\s+");
                if (tokens.length == 2) {
                    try {
                        int r = Integer.parseInt(tokens[0]);
                        int c = Integer.parseInt(tokens[1]);
                        controller.makeMove(r, c);
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid input. Use integers between 0 and 2.");
                    } catch (NotYourTurnException e) {
                        System.out.println("Wait for your turn!");
                    } catch (InvalidMoveException e) {
                        System.out.println("Invalid move: " + e.getMessage());
                    } catch (Exception e) {
                        System.out.println("Error during move submission: " + e.getMessage());
                    }
                } else {
                    System.out.println("Please input exactly two numbers (row and col) or type 'leave'.");
                }
            } else {
                System.out.println("Waiting for opponent (" + state.getTurnOf() + ") to move...");
                synchronized (gameLock) {
                    while (currentBoardState != null && 
                           currentBoardState.getStatus() == GameStatus.ACTIVE && 
                           !currentBoardState.getTurnOf().equals(playerName)) {
                        try {
                            gameLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Prints the board state visualization to stdout.
     *
     * @param state the board state to render
     */
    private void printBoard(BoardState state) {
        System.out.println(state.toString());
    }

    /**
     * Evaluates the final board state and prints the corresponding result summary.
     *
     * @param state the final board state
     * @param leftFlag true if the opponent left before completion
     */
    private void showGameEndResult(BoardState state, boolean leftFlag) {
        GameStatus status = state.getStatus();
        System.out.println("=================================");
        if (status == GameStatus.DRAW) {
            System.out.println("Game over: It's a DRAW!");
        } else if (status == GameStatus.ABANDONED) {
            System.out.println("Game over: ABANDONED!");
            if (leftFlag) {
                System.out.println("Your opponent left the game. You win by default!");
            } else {
                System.out.println("The game was terminated.");
            }
        } else {
            String winner = (status == GameStatus.WON_X) ? state.getPlayerX() : state.getPlayerO();
            if (winner.equals(playerName)) {
                System.out.println("Congratulations! YOU WON!");
            } else {
                System.out.println("Game over. YOU LOST! Winner: " + winner);
            }
        }
        System.out.println("=================================");
    }

    // --- GameEventListener Callbacks ---

    /**
     * {@inheritDoc}
     *
     * @param initialState the initial board state snapshot when the game starts
     */
    @Override
    public void onGameStarted(BoardState initialState) {
        synchronized (gameLock) {
            this.currentBoardState = initialState;
            gameLock.notifyAll();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param newState the updated board state snapshot
     */
    @Override
    public void onGameUpdated(BoardState newState) {
        synchronized (gameLock) {
            this.currentBoardState = newState;
            gameLock.notifyAll();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param opponentName the nickname of the opponent who left
     */
    @Override
    public void onOpponentLeft(String opponentName) {
        synchronized (gameLock) {
            this.opponentLeftFlag = true;
            System.out.println("\n[System] Opponent '" + opponentName + "' left the game!");
            gameLock.notifyAll();
        }
    }
}
