# Threaded package

This directory contains the specialized multithreaded runtime built around the
shared game model.

## Purpose

The package provides the platform-thread execution strategy for Poool. It does
not define a second set of game rules. Instead, it wraps the shared
`GameModel` with a controller thread, asynchronous command submission,
immutable snapshot publication, and worker-based physics stepping.

## Files

- `ThreadedGameRunner.java`
  Main runtime coordinator. Owns the game model, starts threads, accepts shot
  requests, and publishes snapshots.
- `ThreadedGameSnapshot.java`
  Immutable snapshot used by the GUI and tests in threaded mode.
- `ThreadedBotAgent.java`
  Active bot component. Observes snapshots and submits bot shots
  asynchronously.
- `CommandQueueMonitor.java`
  Monitor that stores pending commands for the controller thread.
- `GameCommand.java`
  Command interface executed by the controller on its owned `GameModel`.
- `CommandReceipt.java`
  Small completion object returned to callers that submit asynchronous
  commands.
- `SnapshotStore.java`
  Monitor that stores the latest immutable threaded snapshot and lets readers
  wait for state changes.
- `package-info.java`
  Package-level summary of the threaded runtime responsibilities.

## Relationships

- Uses `model.game.GameModel` as the authoritative gameplay model.
- Uses `model.physics.ThreadedPhysicsEngine` as the injected stepping strategy.
- Is created by `ThreadedPoool`, which handles the Swing-facing launcher loop.
- Receives user shots indirectly from `view.ViewFrame` through the launcher.
- Publishes immutable state consumed by `ThreadedPoool` and by tests.
