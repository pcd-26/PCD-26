# Distributed Tic-Tac-Toe Internal Project Notebook

This document is the internal map for the Java RMI Tic-Tac-Toe project.
It collects the facts that are useful while we simplify code, keep the remote
contracts stable, and prepare the report source.

It is not a public README. It is the working note we should consult before
changing code or adapting the delivery material.

## 1. How to use this notebook

When starting a task:

1. read the relevant section below;
2. jump to the linked source or doc file;
3. check the matching tests;
4. update this notebook if the architecture or report story changes.

## 2. Assignment brief, in our own words

This sub-project implements a distributed Tic-Tac-Toe game using Java RMI.

The important points are:

- a player can create a new game with a unique name;
- a player can join an existing game by name;
- the server owns the authoritative game state;
- clients receive updates through RMI callbacks;
- the lobby and each game must behave correctly under concurrent access.

## 3. What the brief asks for, and where it is covered

| Requirement from the brief | Where it is covered | Status |
| --- | --- | --- |
| create a new game with a name | `pcd.dttt.server.LobbyImpl` | implemented |
| join an existing game by name | `pcd.dttt.server.LobbyImpl` | implemented |
| distributed object computing with Java RMI | `pcd.dttt.common`, `pcd.dttt.server`, `pcd.dttt.client` | implemented |
| concurrent programming principles | synchronized lobby and game state | implemented |
| asynchronous callbacks to clients | `pcd.dttt.client.GameControllerImpl` | implemented |
| immutable board snapshots | `pcd.dttt.common.BoardState` | implemented |

## 4. Source of truth map

### 4.1 Public-facing entry points

- [`README.md`](../README.md)
- [`run-dttt-registry.sh`](../run-dttt-registry.sh)
- [`run-dttt-server.sh`](../run-dttt-server.sh)
- [`run-dttt-cli.sh`](../run-dttt-cli.sh)
- [`run-dttt-gui.sh`](../run-dttt-gui.sh)
- [`test-dttt.sh`](../test-dttt.sh)

### 4.2 Code

- `src/main/java/pcd/dttt/common`
- `src/main/java/pcd/dttt/client`
- `src/main/java/pcd/dttt/server`
- `src/main/java/pcd/dttt/registry`

### 4.3 Tests

- `src/test/java/pcd/dttt/common`
- `src/test/java/pcd/dttt/integration`
- `src/test/java/pcd/dttt/server`

### 4.4 Report source

- `report/`

## 5. Architecture story

### 5.1 Lobby

The lobby is the matchmaking entry point.

What to keep in mind:

- it creates games with unique names;
- it lets players join existing games;
- it prunes finished or abandoned matches;
- it should remain the only place that creates game instances.

### 5.2 Game

Each game instance is the server-side authority for one match.

What to keep in mind:

- it owns the board, turn order, and match status;
- it validates every move on the server;
- it notifies both players through callbacks;
- it should never expose mutable shared state to clients.

### 5.3 Client controller

The client controller bridges remote callbacks and local UI or CLI listeners.

What to keep in mind:

- the GUI and CLI consume events from this controller;
- callbacks must be handled without blocking the game lock;
- listener notification should stay small and explicit.

### 5.4 Immutable snapshot model

The board state sent to clients must be a snapshot, not a live board object.

What to keep in mind:

- it simplifies concurrency;
- it prevents clients from mutating server state;
- it makes tests and callbacks easier to reason about.

## 6. Tests that matter most

The tests should protect the behaviors that refactoring can accidentally break:

- game creation and join semantics;
- invalid or duplicate game names;
- turn alternation;
- board bounds and occupied-cell rejection;
- win, draw, and abandonment logic;
- concurrent lobby and game access;
- immutable board snapshots.

## 7. Report-ready concept list

If we want a short conceptual summary for the report, it is this one:

- server-side ownership of the match;
- Java RMI as the communication mechanism;
- asynchronous client callbacks;
- synchronized state transitions;
- immutable snapshots for clients;
- pruning of finished games.

