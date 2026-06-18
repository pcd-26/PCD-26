# Model package

This directory contains the domain model of Poool: mathematics, physics,
gameplay state, and some reusable monitor utilities.

## Purpose

The `model` package is the core of the project. It contains the data types and
behaviors that define what the game means independently from the chosen runtime
strategy.

The main design split is:

- `common.math`: immutable geometric primitives;
- `physics`: mutable board state and deterministic physics stepping;
- `game`: game rules built on top of the board;
- `concurrent`: reusable monitor-based utilities.

## Subdirectories

### `common/math`

- `P2d.java`
  Immutable 2D point.
- `V2d.java`
  Immutable 2D vector.

These types are shared by physics, view, and tests.

### `physics`

- `Ball.java`
  Physical ball entity with position, radius, mass, and velocity. When desired,
  configurations can derive the mass from the radius through the uniform-material
  helper, so equal material means mass scales with disk area.
- `Board.java`
  Mutable physical state of the match. Owns balls, holes, and low-level events
  such as pocketing and direct cue-ball touches.
- `BoardConf.java`
  Interface for building initial board layouts.
- `Boundary.java`
  Rectangular board boundary.
- `Hole.java`
  Circular hole entity.
- `PhysicsDefaults.java`
  Shared numerical constants for stepping and collision detection.
- `PhysicsStepper.java`
  Strategy interface for advancing a `Board`.
- `PhysicsEngine.java`
  Sequential implementation of `PhysicsStepper`.
- `ThreadedPhysicsEngine.java`
  Worker-based multithreaded implementation of `PhysicsStepper`.
- `SpatialCollisionDetector.java`
  Broad-phase detector used by the sequential physics engine.
- `PhysicsWorker.java`
  Long-lived worker thread used internally by `ThreadedPhysicsEngine`.
- `WorkerCompletionMonitor.java`
  Monitor used to coordinate the completion of one worker phase.

### `physics/config`

- `MinimalBoardConf.java`
  Small deterministic layout used mainly by tests.
- `StandardGameBoardConf.java`
  Default playable layout.
- `LargeBoardConf.java`
  Larger stress configuration.
- `ThousandBallsBoardConf.java`
  Playable stress configuration with 1000 small balls.
- `MassiveBoardConf.java`
  Heavy configuration for performance experiments.

### `game`

- `SequentialGame.java`
  Main game-rule coordinator. Uses the board and applies score, winner,
  readiness, and match lifecycle rules.
- `GameSnapshot.java`
  Immutable logical snapshot exposed to rendering, tests, and benchmarks.
- `Player.java`
  Distinguishes `HUMAN` and `BOT`.
- `GameStatus.java`
  Lifecycle state of the match.
- `GameOverReason.java`
  Explicit terminal reason for a finished game.

### `concurrent`

- `BoundedBuffer.java`
  Minimal blocking producer/consumer buffer interface.
- `BoundedBufferImpl.java`
  Monitor-based bounded buffer implementation.

## Relationships

- `game.SequentialGame` uses `physics.Board`.
- `physics.Board` delegates stepping to a `PhysicsStepper`.
- Both sequential and threaded runtimes reuse the same `model` package.
- The view reads snapshots and copied data from the model, but does not own it.
