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
- `package-info.java`
  Package-level summary of the threaded runtime responsibilities.

## Relationships

- Uses `model.game.GameModel` as the authoritative gameplay model.
- Uses `model.physics.threaded.ThreadedPhysicsEngine` as the injected stepping
  strategy.
- Reuses `runtime` bot, command, receipt, queue, and snapshot supports that are
  shared with the task-based runner.
- Is created by `ThreadedPoool`, which handles the Swing-facing launcher loop.
- Receives user shots indirectly from `view.ViewFrame` through the launcher.
- Publishes immutable state consumed by `ThreadedPoool` and by tests.
