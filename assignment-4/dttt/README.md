# Distributed Tic-Tac-Toe with Java RMI

This project implements a distributed Tic-Tac-Toe game using Java RMI.
It supports:

- creating a game with a unique name;
- joining an existing game by name;
- running registry, server, and clients in separate JVM processes;
- server-side validation of all moves and game invariants;
- asynchronous server-to-client callbacks using RMI remote interfaces;
- CLI and Swing GUI clients.

## Architecture

The system is split into three roles:

- `registry`: standalone RMI registry process;
- `server`: exports the lobby and game rooms and owns all match state;
- `client`: exports a callback object and interacts with the server via remote stubs.

Remote objects:

- `Lobby` is the matchmaking entry point bound in the registry;
- `Game` is the remote match controller returned by the lobby;
- `PlayerClient` is the callback interface implemented by the client;
- `BoardState` is the immutable snapshot sent from server to client.

The server keeps the authoritative board state, turn order, and match status.
Clients receive only snapshots or remote stubs, never mutable shared state.

### Concurrency model

- `LobbyImpl` serializes create/join/list operations and prunes finished games.
- `GameImpl` synchronizes all state mutations per game.
- Remote callbacks are executed outside the game lock using virtual threads.
- The GUI forwards remote events to the Swing EDT.
- The CLI waits for state changes using `wait()`/`notifyAll()` instead of busy waiting.

### Lifecycle

1. Start the registry.
2. Start the server and bind the lobby into the registry.
3. Start one or more clients.
4. A player creates a room.
5. Another player joins the room.
6. Players make alternating moves until win, draw, or abandonment.
7. Finished rooms are pruned from the lobby and their remote resources are cleaned up.

## Project Structure

```text
src/main/java/pcd/dttt
├── Main.java                  # Unified launcher for registry/server/client
├── common/                    # Remote interfaces, DTOs, enums, exceptions
├── client/                    # CLI/GUI client and RMI callback bridge
├── server/                    # Lobby and game implementations
└── registry/RegistryMain.java # Standalone RMI registry entry point

src/test/java/pcd/dttt
├── server/                    # Unit and concurrency tests
└── integration/               # Local RMI integration tests
```

## Configuration

Defaults:

- registry host: `localhost`
- registry port: `1099`
- service name: `Lobby`

Command line usage:

```bash
java -jar target/ex2-distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar registry [port]
java -jar target/ex2-distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar server [registryHost] [registryPort] [serviceName]
java -jar target/ex2-distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar client [host] [port] [serviceName] [--cli]
```

The GUI connection form also allows the host, port, and service name to be edited.

## Build and Test

From `assignment-4/dttt`:

```bash
mvn -B clean verify
```

The `test-dttt.sh` and `test-dttt.ps1` helpers run the same command.

## Report

The report source lives in `report/`.
Use `make -C report` to build `report.pdf`.

## Startup Examples

### Registry

```bash
./run-dttt-registry.sh 1099
```

### Server

```bash
./run-dttt-server.sh localhost 1099 Lobby
```

### CLI client

```bash
./run-dttt-cli.sh localhost 1099 Lobby
```

### GUI client

```bash
./run-dttt-gui.sh localhost 1099 Lobby
```

## Game Rules

- The creator becomes Player X.
- The first joiner becomes Player O.
- Exactly two players are allowed per game.
- Player X always starts.
- Moves are rejected if the game is waiting, finished, out of bounds, occupied, or played out of turn.
- Wins are detected horizontally, vertically, and diagonally.
- Full boards without a winner end in a draw.
- Leaving or disconnecting abandons the game.

## Tests

The suite covers:

- game creation and joining;
- duplicate and missing games;
- third-player rejection;
- move validation;
- turn alternation;
- win and draw conditions;
- immutable board snapshots;
- concurrent create/join races;
- concurrent moves;
- isolated local RMI integration;
- cleanup of exported remote objects and executors.

## Assumptions and Limitations

- The registry must be running before the server starts.
- RMI uses hostnames and ports directly; there is no authentication layer.
- The GUI is intended for interactive use and falls back to CLI only when the environment is headless.
- The server keeps finished rooms long enough to deliver final callbacks, then prunes them on the next lobby access.
- There is no persistence across JVM restarts.
