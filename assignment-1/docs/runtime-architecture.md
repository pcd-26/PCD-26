# Poool runtime architecture

This is the source of truth for explaining Assignment 1. The architecture has
one application flow, one game loop, one domain model, and one parallel physics
algorithm. The required implementations differ only at explicit execution
policy seams.

## Complete flow

```text
Swing / Bot
    -> CommandMailbox
    -> GameLoop
    -> GameModel
    -> Board
    -> PhysicsStepper
         |- SequentialPhysicsEngine
         `- ParallelPhysicsEngine
              `- RangeScheduler
                   |- PlatformThreadRangeScheduler
                   `- ExecutorRangeScheduler

GameLoop -> RuntimeGameSnapshot -> launcher -> View
```

## The five concepts to explain

### 1. Launcher lifecycle

`ThreadedPoool` and `TaskBasedPoool` each own their GUI lifecycle directly. They
handle render refresh, restart, FPS, bot preview, and shutdown around the
`GameRuntime` they construct.

### 2. `GameLoop`

This is the single-writer runtime protocol. One tick always does the same three
things:

1. drain `CommandMailbox`;
2. call `GameModel.step` if the match is active;
3. publish an immutable `RuntimeGameSnapshot`.

`ThreadedGameRunner` invokes that tick from a normal platform thread.
`TaskBasedGameRunner` invokes it from a single-thread scheduled executor. No
game rules are duplicated between them.

### 3. `GameModel`

The model owns gameplay meaning: countdown, scores, shot acceptance, match
status, winner, game-over reason, and simulated-time metrics. It delegates
physical movement to `Board`.

### 4. `Board`

The board owns mutable balls, holes, bounds, pocket state, and low-level contact
events. It does not choose the winner. It delegates each time step through the
small `PhysicsStepper` interface.

### 5. Physics engine

`SequentialPhysicsEngine` is the reference implementation.
`ParallelPhysicsEngine` is the only parallel algorithm. Its `RangeScheduler`
decides whether ranges run on explicit platform workers or Executor tasks. See
[`physics-engines-and-concurrency.md`](physics-engines-and-concurrency.md).

## Ownership and synchronization

- The controller invoking `GameLoop.tick` is the only writer of `GameModel`.
- `CommandMailbox` is the custom monitor between Swing/bot producers and that
  controller.
- A physics step holds one board ownership boundary.
- Physics workers touch disjoint balls or private accumulators.
- Collision and score-affecting commits are serialized.
- Swing consumes immutable/copied snapshots and never sees live mutable balls.

## Required implementations

| Version | Controller | Physics ranges |
| --- | --- | --- |
| Sequential | application loop | direct sequential code |
| Platform-thread | `ThreadedGameRunner` platform thread | reusable `PhysicsWorker`s + `WorkerCompletionMonitor` |
| Task-based | scheduled Executor controller | fixed `ExecutorService` + `Future` barrier |

The platform-thread version does not use Executor Framework for physics. The
task-based version does. Both reuse the same model and numerical kernel.

## Input and rendering

Human input becomes a queued `shootHuman` operation. The bot independently
observes snapshots and queues `shootBot`. A receipt completes when the
controller executes or rejects the command. After the tick, the new snapshot
flows into `ViewModel` and Swing repaints it on the EDT.

## Package map

- `pcd.poool`: three launchers with separate top-level runtime entry points.
- `pcd.poool.runtime`: `GameRuntime`, `GameLoop`, `CommandMailbox`, bot,
  configuration, and runtime snapshot.
- `pcd.poool.model.game`: game rules and logical snapshot.
- `pcd.poool.model.physics.common`: board and physical entities.
- `pcd.poool.model.physics.parallel`: shared parallel kernel and scheduler seam.
- `pcd.poool.model.physics.threaded`: platform-thread scheduler and facade.
- `pcd.poool.model.physics.taskbased`: Executor scheduler and facade.
- `pcd.poool.view`: Swing presentation.
- `pcd.poool.benchmark`: development-only measurement infrastructure, excluded
  from the packaged game JAR.
