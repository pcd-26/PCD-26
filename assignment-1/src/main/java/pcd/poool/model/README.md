# Model package

This directory contains the domain model of Poool: mathematics, physics,
gameplay state, and some reusable monitor utilities.

## Purpose

The `model` package is the core of the project. It contains the data types and
behaviors that define what the game means independently from the chosen runtime
strategy.

The main design split is:

- `common.math`: immutable geometric primitives;
- `physics.common`: shared board state, entities, and helpers;
- `physics.sequential`: sequential physics stepping;
- `physics.threaded`: long-lived worker-thread physics stepping;
- `physics.taskbased`: executor-based task physics stepping;
- `physics.config`: reusable board configurations;
- `game`: game rules built on top of the board;
- `concurrent`: reusable monitor-based utilities.

## Subdirectories

### `common/math`

- `P2d.java`
  Immutable 2D point.
- `V2d.java`
  Immutable 2D vector.

These types are shared by physics, view, and tests.

### `physics/common`

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
- `SpatialCollisionDetector.java`
  Broad-phase detector used by all physics engines.

### `physics/sequential`

- `SequentialPhysicsEngine.java`
  Sequential implementation of `PhysicsStepper`.

### `physics/threaded`

- `ThreadedPhysicsEngine.java`
  Worker-based multithreaded implementation of `PhysicsStepper`. It computes
  collision contributions in parallel and applies accumulated position/velocity
  deltas deterministically once per ball. Candidate pairs are split across
  worker threads, each worker fills private per-ball delta arrays, and the
  controller merges those arrays before the final per-ball apply phase.
- `ThreadedPhysicsEngine.java`
  Platform-threaded engine based on worker-owned spatial cells, forward-neighbor
  collision scans, and sparse per-ball delta accumulation to reduce the global
  coordination cost of the collision phase on large workloads.
- `PhysicsWorker.java`
  Long-lived worker thread used internally by `ThreadedPhysicsEngine`.
- `WorkerCompletionMonitor.java`
  Monitor used to coordinate the completion of one worker phase.

### `physics/taskbased`

- `TaskBasedPhysicsEngine.java`
  Executor-based physics implementation that preserves the same board
  ownership model while scheduling work through an `ExecutorService`.
  Integration, hole checks, spatial-grid construction, and collision handling
  are modeled as tasks. Collision pairs are packed in compact `long` values.
  Small contact sets are resolved as independent collision rounds; larger
  contact sets use parallel accumulated impulse/delta computation followed by a
  deterministic merge and per-ball apply phase.

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

- `GameModel.java`
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

- `game.GameModel` uses `physics.common.Board`.
- `physics.Board` delegates stepping to a `PhysicsStepper`.
- Sequential, threaded, and task-based runtimes reuse the same `model`
  package.
- The view reads snapshots and copied data from the model, but does not own it.
