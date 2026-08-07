# Poool Runtime Architecture

This document describes the architecture implemented in `assignment-1/src/main/java/pcd/poool`.
It is intentionally code-oriented: the goal is to explain what each component
does, who owns which state, and how data and control flow across the system.

## 1. Architectural overview

The project is split into four main layers:

1. `model.common.math`
   Immutable geometric value objects.
2. `model.physics`
   Mutable board state and deterministic physics stepping.
3. `model.game`
   Game rules built on top of the board and physics layer.
4. `view` and runtime launchers
   Swing rendering plus the sequential, platform-thread, and task-based execution policies.

The key design choice is that the game model and the physics logic stay shared,
while the execution strategy changes around them.

That means:

- `SequentialPoool` runs the whole game from one loop.
- `ThreadedGameRunner` runs the same logical game from a controller thread and
  delegates expensive physics phases to worker threads.
- `TaskBasedGameRunner` runs the same logical game from a controller executor
  and delegates expensive physics phases to executor tasks.
- `Board` and `GameModel` are reused in all modes.

## 2. Package map

### `pcd.poool`

- `SequentialPoool`
  Playable sequential launcher.
- `ThreadedPoool`
  Playable platform-thread launcher.
- `TaskBasedPoool`
  Playable task-based launcher.
- `PooolApplication`
  Shared GUI, rendering, restart, and shutdown lifecycle for both concurrent
  launchers.

The three public launchers select an execution strategy. `PooolApplication`
contains the shared concurrent GUI lifecycle and keeps refreshing `ViewModel`.

### `pcd.poool.model.common.math`

- `P2d`
  Immutable 2D point.
- `V2d`
  Immutable 2D vector.

These are the lowest-level shared types and are used by physics, view, tests,
and game logic.

### `pcd.poool.model.physics`

- `Board`
  Owns the mutable physical state of the match.
- `Ball`, `Boundary`, `Hole`
  Core physical entities.
- `BoardConf`
  Supplies initial board layouts.
- `PhysicsStepper`
  Strategy interface for advancing a board.
- `PhysicsEngine`
  Sequential deterministic implementation of `PhysicsStepper`.
- `ThreadedPhysicsEngine`
  Worker-based multithreaded implementation of `PhysicsStepper`.
- `SpatialCollisionDetector`
  Broad-phase collision candidate detector used by sequential physics.
- `PhysicsWorker`, `WorkerCompletionMonitor`
  Internal support classes used by `ThreadedPhysicsEngine`.
- `PhysicsDefaults`
  Shared numerical defaults.

### `pcd.poool.model.physics.config`

- `MinimalBoardConf`
- `StandardGameBoardConf`
- `LargeBoardConf`
- `ThousandBallsBoardConf`
- `MassiveBoardConf`

These classes build deterministic initial scenarios used by gameplay, tests,
and benchmarks.

### `pcd.poool.model.game`

- `GameModel`
  Central game-rule coordinator.
- `GameSnapshot`
  Immutable logical state exposed to view, tests, and benchmarks.
- `Player`, `GameStatus`, `GameOverReason`
  Domain enums.

This package does not own low-level physics integration. It uses `Board` as
the physical substrate and applies higher-level rules such as score, readiness,
termination, and winner calculation.

### `pcd.poool.threaded`

- `ThreadedGameRunner`
  Main multithreaded runtime coordinator.

This package contains the concurrency-specific runtime around the shared game
model.

### `pcd.poool.taskbased`

- `TaskBasedGameRunner`
  Main executor-based runtime coordinator.

This package mirrors `pcd.poool.threaded`, but the controller uses a scheduled
executor and the physics phase uses task submission and futures instead of
long-lived worker threads.

### `pcd.poool.runtime`

- `CommandMailbox`
  Monitor that queues model operations and owns their completion receipts.
- `GameRuntime`
  Small common API implemented by both concurrent runners and consumed by the
  shared application.
- `GameLoop`
  Shared single-writer tick: drain commands, advance `GameModel`, and publish
  the immutable snapshot.
- `GameRuntimeConfig`
  One validated configuration shared by the platform-thread and Executor
  drivers.
- `BotAgent`
  Active bot component that observes snapshots and submits bot shots.
- `RuntimeGameSnapshot`
  Immutable snapshot published by concurrent runners.

### `pcd.poool.view` and `pcd.poool.view.board`

- `View`
  Facade used by launchers.
- `ViewFrame`
  Swing window, painting, and input translation.
- `ViewModel`
  Thread-safe rendering data consumed by Swing.
- `RenderSynch`
  Render synchronization helper.

The view never owns the game state. It only reads immutable or copied data and
turns user input into callbacks.

### Other supporting packages

- `pcd.poool.benchmark`
  Micro-benchmarks and integrated runtime benchmarks.
- `assignment-1/reference/sketch02`
  Original active-controller and bounded-buffer examples, kept outside the
  production source tree because the playable runtimes use their specialized
  command monitor instead.

## 3. Core responsibilities

### `Board`: physical state owner

`Board` is the mutable container for:

- small balls;
- human cue ball;
- bot cue ball;
- board boundaries;
- holes;
- low-level scoring and pocketing events.

`Board` does not decide who won or whether the match is finished. It only:

- updates the physical state through `updateState(dt)`;
- lets callers ask if balls are moving or kickable;
- records events such as direct cue-ball touches and pocketed balls.

`Board` delegates time advancement to a `PhysicsStepper`, so the same board can
be stepped either sequentially or with worker threads.

### `PhysicsStepper`: execution-policy seam inside the physics layer

`PhysicsStepper` is the abstraction that says how one mutable board step is
computed.

Implementations:

- `SequentialPhysicsEngine`: sequential stepping.
- `ParallelPhysicsEngine`: one shared parallel algorithm.
- `ThreadedPhysicsEngine`: facade selecting reusable platform-thread workers.
- `TaskBasedPhysicsEngine`: facade selecting a fixed Executor pool.

Both mutate the same `Board` API and preserve the same game semantics at the
board level.

### `GameModel`: gameplay coordinator

`GameModel` is the main domain-level component. It owns:

- score;
- game status;
- winner;
- game-over reason;
- timing metrics;
- player shot acceptance;
- bot shot choice;
- consumption of scoring events emitted by `Board`.

It uses `Board` for low-level state and physics. In other words:

- `Board` knows what physically happened.
- `GameModel` decides what that means for the match.

This is why `GameModel` is reused even in the threaded runtime: the
project keeps one authoritative set of game rules.

## 4. Runtime wiring

### 4.1 Sequential runtime

`SequentialPoool` builds:

- one `GameModel`;
- one shared `ViewModel`;
- one `View`.

Main loop responsibilities:

1. advance game state with `game.step(...)`;
2. trigger bot shots when the bot is ready;
3. copy current board and game data into `ViewModel`;
4. request rendering through `View.render()`.

In this mode, one loop owns all mutations. Swing still runs on the EDT for
painting and input, but game state progression remains serialized by the main
runtime loop.

### 4.2 Concurrent runtimes

`ThreadedPoool` builds:

- one `ThreadedGameRunner`;
- one shared `ViewModel`;
- one `View`.

`ThreadedGameRunner` then assembles:

- one shared `GameLoop`, which owns `GameModel`;
- one `ThreadedPhysicsEngine`;
- one controller thread;
- one optional bot thread;
- one `CommandMailbox` inside the shared `GameLoop`.

`TaskBasedPoool` uses the same `GameLoop` and changes only the driver: a
scheduled executor invokes the tick, while `TaskBasedPhysicsEngine` uses a
fixed executor pool for physics work.

The controller thread is the single writer of `GameModel`.

External threads never mutate the game directly:

- Swing input submits commands through `shootHuman(...)`;
- the bot thread submits commands through `shootBot(...)`;
- the GUI reads the latest `RuntimeGameSnapshot` published by `GameLoop`.

This preserves a clear ownership rule:

- compute in parallel where useful;
- commit game-rule mutations from one place.

## 5. Relationships: who uses what

### Entry points

- `SequentialPoool` uses `GameModel`, `View`, and `ViewModel`.
- `ThreadedPoool` uses `ThreadedGameRunner`, `View`, and `ViewModel`.
- `TaskBasedPoool` uses `TaskBasedGameRunner`, `View`, and `ViewModel`.

### Game model

- `GameModel` uses `Board`.
- `GameModel` optionally injects a `PhysicsStepper` into `Board`.
- `GameModel` produces `GameSnapshot`.

### Physics

- `Board` uses `PhysicsStepper` through `updateState(...)`.
- `PhysicsEngine` uses `Board` and `SpatialCollisionDetector`.
- `ParallelPhysicsEngine` uses `Board` and delegates ranges to `RangeScheduler`.
- `ThreadedPhysicsEngine` selects `PlatformThreadRangeScheduler`,
  `PhysicsWorker`, and `WorkerCompletionMonitor`.
- `TaskBasedPhysicsEngine` selects `ExecutorRangeScheduler`.

### Concurrent runtimes

- Both runners use the shared `GameLoop`, their physics engine, and `BotAgent`.
- `BotAgent` reads the latest loop snapshot and submits its shot to
  `CommandMailbox` through the loop.
- `CommandMailbox` executes queued model operations only when `GameLoop` drains
  it on the controller owner.
- `GameLoop` publishes `RuntimeGameSnapshot` and provides monitor-based waiting
  for tests and other observers.

### View

- `View` creates and owns `ViewFrame`.
- `ViewFrame` reads `ViewModel` and sends callbacks to the runtime.
- `ViewModel` stores copied rendering data and `GameSnapshot`.
- `ThreadedPoool` copies `RuntimeGameSnapshot` into `ViewModel`.
- `SequentialPoool` copies `Board` and `GameSnapshot` into `ViewModel`.

## 6. Data flow

### 6.1 Human input

1. The user presses keys or drags the mouse on `ViewFrame`.
2. `ViewFrame` converts the gesture into a `V2d` shot.
3. The shot callback reaches the current launcher.
4. In sequential mode, the launcher calls `GameModel.shootHuman(...)`.
5. In threaded mode, the launcher calls `ThreadedGameRunner.shootHuman(...)`,
   which enqueues a model operation in `CommandMailbox`.
6. In task-based mode, the launcher calls `TaskBasedGameRunner.shootHuman(...)`,
   which enqueues the same model operation in `CommandMailbox`.
7. The controller eventually executes the command on `GameModel`.

### 6.2 Physics progression

1. A runtime decides when to call `step(...)`.
2. `GameModel.step(...)` calls `Board.updateState(...)`.
3. `Board.updateState(...)` delegates to its `PhysicsStepper`.
4. The physics stepper mutates positions, velocities, collisions, and holes.
5. `Board` records low-level events.
6. `GameModel` consumes those events and updates score and lifecycle state.

### 6.3 Rendering

Sequential mode:

1. `SequentialPoool` reads current board/game state.
2. It copies it into `ViewModel`.
3. `View.render()` asks the Swing frame to repaint.

Threaded mode:

1. The controller thread publishes a `RuntimeGameSnapshot`.
2. `ThreadedPoool` reads that immutable snapshot.
3. It copies it into `ViewModel`.
4. `View.render()` asks the Swing frame to repaint.

The important part is that Swing renders copied or immutable data, not the
authoritative mutable model.

## 7. Threading and ownership rules

The code follows a strong single-writer rule.

- In sequential mode, the main loop is the only writer of `GameModel`.
- In threaded mode, the controller thread is the only writer of `GameModel`.
- `ThreadedPhysicsEngine` uses worker threads for internal phases, but the step
  still happens under controller-owned orchestration.
- Swing never mutates `Board` or `GameModel` directly.
- The bot never mutates `Board` or `GameModel` directly.

This is the central architectural constraint that keeps the code understandable
and reduces races.

## 8. Why the architecture is structured this way

The project tries to preserve three goals at once:

1. one shared semantic model of the game;
2. interchangeable execution strategies;
3. safe GUI interaction.

The resulting split is:

- physics knows how bodies move;
- game logic knows how scoring and termination work;
- runtimes decide when and on which threads those operations happen;
- the GUI only presents derived state and submits requests.

That separation is what makes the threaded version possible without forking the
whole game model.
