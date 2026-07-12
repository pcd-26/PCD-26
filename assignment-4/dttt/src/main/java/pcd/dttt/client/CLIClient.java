package pcd.dttt.client;

import java.rmi.RemoteException;
import java.util.List;
import java.util.Scanner;
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
 * Command Line Interface client for the Distributed Tic-Tac-Toe game.
 */
public class CLIClient implements GameEventListener {
    private final Lobby lobby;
    private final Scanner scanner;
    private final Object gameLock = new Object();

    private String playerName;
    private PlayerClientImpl clientStub;
    
    private Game currentGame;
    private BoardState currentBoardState;
    private boolean opponentLeftFlag = false;

    public CLIClient(Lobby lobby) {
        this.lobby = lobby;
        this.scanner = new Scanner(System.in);
    }

    public void start() {
        System.out.println("=== Welcome to Distributed Tic-Tac-Toe ===");
        
        try {
            this.clientStub = new PlayerClientImpl(this);
        } catch (RemoteException e) {
            System.err.println("Failed to export client callback stub: " + e.getMessage());
            return;
        }

        // Get player name
        while (playerName == null || playerName.isBlank()) {
            System.out.print("Enter your name: ");
            playerName = scanner.nextLine().trim();
            if (playerName.isBlank()) {
                System.out.println("Name cannot be empty!");
            }
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
        
        // Clean up client RMI export on exit
        try {
            java.rmi.server.UnicastRemoteObject.unexportObject(clientStub, true);
        } catch (Exception e) {
            // Ignore
        }
    }

    private void showMainMenu() {
        System.out.println("\n--- Main Menu ---");
        System.out.println("1. Create a new game");
        System.out.println("2. Join an existing game");
        System.out.println("3. List games waiting for players");
        System.out.println("4. Exit");
    }

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
                currentGame = null;
                opponentLeftFlag = false;
            }
            
            Game game = lobby.createGame(gameName, playerName, clientStub);
            
            synchronized (gameLock) {
                currentGame = game;
                currentBoardState = game.getBoardState();
            }
            
            System.out.println("Game '" + gameName + "' created successfully.");
            playGameLoop();

        } catch (GameAlreadyExistsException e) {
            System.out.println("Error: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Error creating game: " + e.getMessage());
        }
    }

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
                currentGame = null;
                opponentLeftFlag = false;
            }

            Game game = lobby.joinGame(gameName, playerName, clientStub);
            
            synchronized (gameLock) {
                currentGame = game;
                currentBoardState = game.getBoardState();
            }

            System.out.println("Joined game '" + gameName + "' successfully.");
            playGameLoop();

        } catch (GameNotFoundException | GameFullException e) {
            System.out.println("Error: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("Error joining game: " + e.getMessage());
        }
    }

    private void listWaitingGames() {
        try {
            List<String> waiting = lobby.getWaitingGames();
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

    private void playGameLoop() {
        // Wait for the game to start if waiting
        synchronized (gameLock) {
            while (currentBoardState != null && currentBoardState.getStatus() == GameStatus.WAITING) {
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
                        currentGame.leaveGame(playerName);
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
                        currentGame.makeMove(playerName, r, c);
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid input. Use integers between 0 and 2.");
                    } catch (NotYourTurnException e) {
                        System.out.println("Wait for your turn!");
                    } catch (InvalidMoveException e) {
                        System.out.println("Invalid move: " + e.getMessage());
                    } catch (Exception e) {
                        System.out.println("RMI Error during move: " + e.getMessage());
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

    private void printBoard(BoardState state) {
        System.out.println(state.toString());
    }

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

    @Override
    public void onGameStarted(BoardState initialState) {
        synchronized (gameLock) {
            this.currentBoardState = initialState;
            gameLock.notifyAll();
        }
    }

    @Override
    public void onGameUpdated(BoardState newState) {
        synchronized (gameLock) {
            this.currentBoardState = newState;
            gameLock.notifyAll();
        }
    }

    @Override
    public void onOpponentLeft(String opponentName) {
        synchronized (gameLock) {
            this.opponentLeftFlag = true;
            System.out.println("\n[System] Opponent '" + opponentName + "' left the game!");
            gameLock.notifyAll();
        }
    }
}
