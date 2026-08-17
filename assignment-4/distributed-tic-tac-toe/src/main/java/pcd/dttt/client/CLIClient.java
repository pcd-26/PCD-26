package pcd.dttt.client;

import java.util.List;
import java.util.Scanner;
import pcd.dttt.common.BoardState;
import pcd.dttt.common.GameStatus;
import pcd.dttt.common.exceptions.InvalidMoveException;
import pcd.dttt.common.exceptions.NotYourTurnException;

// Text client for the distributed Tic-Tac-Toe game.
public class CLIClient implements GameEventListener {
    private final GameController gameController;
    private final String registryHost;
    private final int registryPort;
    private final String lobbyBindingName;
    private final Scanner inputScanner;
    private final Object boardStateLock = new Object();

    private String localPlayerName;
    private BoardState latestBoardState;
    private boolean opponentLeft;

    // Prepares the CLI with its connection settings.
    public CLIClient(GameController gameController, String registryHost, int registryPort, String lobbyBindingName) {
        this.gameController = gameController;
        this.registryHost = registryHost;
        this.registryPort = registryPort;
        this.lobbyBindingName = lobbyBindingName;
        this.inputScanner = new Scanner(System.in);
    }

    // Runs the connection phase and the main menu loop.
    public void start() {
        System.out.println("=== Welcome to Distributed Tic-Tac-Toe ===");

        while (localPlayerName == null || localPlayerName.isBlank()) {
            System.out.print("Enter your name: ");
            localPlayerName = inputScanner.nextLine().trim();
            if (localPlayerName.isBlank()) {
                System.out.println("Name cannot be empty!");
            }
        }

        try {
            System.out.println("Connecting to lobby...");
            gameController.connect(registryHost, registryPort, lobbyBindingName, localPlayerName);
            gameController.registerEventListener(this);
        } catch (Exception exception) {
            System.err.println("Connection failed: " + exception.getMessage());
            return;
        }

        boolean shouldExit = false;
        while (!shouldExit) {
            showMainMenu();
            System.out.print("Choose an option: ");
            String selectedOption = inputScanner.nextLine().trim();
            switch (selectedOption) {
                case "1" -> createGameFlow();
                case "2" -> joinGameFlow();
                case "3" -> printWaitingGames();
                case "4" -> {
                    shouldExit = true;
                    System.out.println("Goodbye!");
                }
                default -> System.out.println("Invalid option. Please choose between 1 and 4.");
            }
        }

        gameController.disconnect();
    }

    // Prints the available menu actions.
    private void showMainMenu() {
        System.out.println("\n--- Main Menu ---");
        System.out.println("1. Create a new game");
        System.out.println("2. Join an existing game");
        System.out.println("3. List games waiting for players");
        System.out.println("4. Exit");
    }

    // Creates a room and enters the match loop.
    private void createGameFlow() {
        System.out.print("Enter game name: ");
        String requestedGameName = inputScanner.nextLine().trim();
        if (requestedGameName.isBlank()) {
            System.out.println("Game name cannot be empty.");
            return;
        }

        try {
            // Reset local match state before asking the server to create the room.
            synchronized (boardStateLock) {
                latestBoardState = null;
                opponentLeft = false;
            }

            gameController.createGame(requestedGameName);
            System.out.println("Game '" + requestedGameName + "' created successfully.");
            runMatchLoop();
        } catch (Exception exception) {
            System.out.println("Error creating game: " + exception.getMessage());
        }
    }

    // Joins a room and enters the match loop.
    private void joinGameFlow() {
        System.out.print("Enter game name to join: ");
        String requestedGameName = inputScanner.nextLine().trim();
        if (requestedGameName.isBlank()) {
            System.out.println("Game name cannot be empty.");
            return;
        }

        try {
            // Reset local match state before asking the server to join the room.
            synchronized (boardStateLock) {
                latestBoardState = null;
                opponentLeft = false;
            }

            gameController.joinGame(requestedGameName);
            System.out.println("Joined game '" + requestedGameName + "' successfully.");
            runMatchLoop();
        } catch (Exception exception) {
            System.out.println("Error joining game: " + exception.getMessage());
        }
    }

    // Prints the rooms that are still waiting for a second player.
    private void printWaitingGames() {
        try {
            List<String> waitingGameNames = gameController.getWaitingGames();
            if (waitingGameNames.isEmpty()) {
                System.out.println("No games are currently waiting for players.");
            } else {
                System.out.println("Games waiting for players:");
                for (String waitingGameName : waitingGameNames) {
                    System.out.println("- " + waitingGameName);
                }
            }
        } catch (Exception exception) {
            System.out.println("Error fetching game list: " + exception.getMessage());
        }
    }

    // Runs the match loop until the game ends or the player leaves.
    private void runMatchLoop() {
        // Wait until the server sends the initial playable state.
        synchronized (boardStateLock) {
            while (latestBoardState == null || latestBoardState.status() == GameStatus.WAITING) {
                System.out.println("Waiting for an opponent to join...");
                try {
                    boardStateLock.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        System.out.println("\n=================================");
        System.out.println("       THE MATCH HAS STARTED     ");
        System.out.println("=================================");

        while (true) {
            BoardState currentBoardSnapshot;
            boolean opponentHasLeft;
            synchronized (boardStateLock) {
                currentBoardSnapshot = latestBoardState;
                opponentHasLeft = opponentLeft;
            }

            if (currentBoardSnapshot == null) {
                break;
            }

            // Stop the loop once the server reports a terminal state.
            if (currentBoardSnapshot.status() != GameStatus.ACTIVE) {
                System.out.println("\nFinal Board:");
                printBoard(currentBoardSnapshot);
                printMatchResult(currentBoardSnapshot, opponentHasLeft);
                break;
            }

            printBoard(currentBoardSnapshot);

            // The local player can submit a move only on their turn.
            if (currentBoardSnapshot.turnOf().equals(localPlayerName)) {
                System.out.println("It's your turn!");
                System.out.print("Enter coordinates (row col, e.g. '0 1') or 'leave': ");
                String userInput = inputScanner.nextLine().trim();

                if (userInput.equalsIgnoreCase("leave")) {
                    try {
                        gameController.leaveGame();
                    } catch (Exception exception) {
                        System.out.println("Error leaving game: " + exception.getMessage());
                    }
                    break;
                }

                String[] coordinateTokens = userInput.split("\\s+");
                if (coordinateTokens.length == 2) {
                    try {
                        int selectedRow = Integer.parseInt(coordinateTokens[0]);
                        int selectedColumn = Integer.parseInt(coordinateTokens[1]);
                        gameController.makeMove(selectedRow, selectedColumn);

                        // Wait for the server callback so the next loop iteration
                        // observes the updated board instead of the stale snapshot.
                        synchronized (boardStateLock) {
                            while (latestBoardState == currentBoardSnapshot) {
                                boardStateLock.wait();
                            }
                        }
                    } catch (NumberFormatException exception) {
                        System.out.println("Invalid input. Use integers between 0 and 2.");
                    } catch (NotYourTurnException exception) {
                        System.out.println("Wait for your turn!");
                    } catch (InvalidMoveException exception) {
                        System.out.println("Invalid move: " + exception.getMessage());
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception exception) {
                        System.out.println("Error during move submission: " + exception.getMessage());
                    }
                } else {
                    System.out.println("Please input exactly two numbers (row and col) or type 'leave'.");
                }
            } else {
                System.out.println("Waiting for opponent (" + currentBoardSnapshot.turnOf() + ") to move...");
                synchronized (boardStateLock) {
                    while (latestBoardState != null
                        && latestBoardState.status() == GameStatus.ACTIVE
                        && !latestBoardState.turnOf().equals(localPlayerName)) {
                        try {
                            boardStateLock.wait();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }
    }

    // Prints one board snapshot.
    private void printBoard(BoardState boardState) {
        System.out.println(boardState);
    }

    // Prints the final outcome of the match.
    private void printMatchResult(BoardState finalBoardState, boolean opponentHasLeft) {
        GameStatus finalStatus = finalBoardState.status();
        System.out.println("=================================");
        if (finalStatus == GameStatus.DRAW) {
            System.out.println("Game over: It's a DRAW!");
        } else if (finalStatus == GameStatus.ABANDONED) {
            System.out.println("Game over: ABANDONED!");
            if (opponentHasLeft) {
                System.out.println("Your opponent left the game. You win by default!");
            } else {
                System.out.println("The game was terminated.");
            }
        } else {
            String winnerName = finalStatus == GameStatus.WON_X ? finalBoardState.playerX() : finalBoardState.playerO();
            if (winnerName.equals(localPlayerName)) {
                System.out.println("Congratulations! YOU WON!");
            } else {
                System.out.println("Game over. YOU LOST! Winner: " + winnerName);
            }
        }
        System.out.println("=================================");
    }

    // Stores the initial board state and wakes the waiting CLI thread.
    @Override
    public void onGameStarted(BoardState initialState) {
        synchronized (boardStateLock) {
            latestBoardState = initialState;
            boardStateLock.notifyAll();
        }
    }

    // Stores a fresh board state and wakes the waiting CLI thread.
    @Override
    public void onGameUpdated(BoardState updatedState) {
        synchronized (boardStateLock) {
            latestBoardState = updatedState;
            boardStateLock.notifyAll();
        }
    }

    // Marks the opponent as gone and wakes the waiting CLI thread.
    @Override
    public void onOpponentLeft(String opponentName) {
        synchronized (boardStateLock) {
            opponentLeft = true;
            System.out.println("\n[System] Opponent '" + opponentName + "' left the game!");
            boardStateLock.notifyAll();
        }
    }
}
