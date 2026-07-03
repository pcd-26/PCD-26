# Assignment 1 Physics Architecture Analysis

This report traces one simulation tick through the current assignment-1 physics
pipeline and identifies where work is sequential, parallel, copied, merged, or
serialized.

## Scope

Relevant classes:

- `pcd.poool.model.game.GameModel`
- `pcd.poool.model.physics.common.Board`
- `pcd.poool.model.physics.common.PhysicsStepper`
- `pcd.poool.model.physics.sequential.PhysicsEngine`
- `pcd.poool.model.physics.threaded.ThreadedPhysicsEngine`
- `pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine`
- `pcd.poool.model.physics.threaded.PhysicsWorker`
- `pcd.poool.model.physics.threaded.WorkerCompletionMonitor`
- `pcd.poool.threaded.ThreadedGameRunner`
- `pcd.poool.taskbased.TaskBasedGameRunner`

## Current Call Flow For One Tick

The shared top-level flow is:

1. The runner/controller thread drains queued commands.
2. The controller checks `game.snapshot().isFinished()`.
3. If the game is still running, the controller calls `game.step(tickMillis)`.
4. `GameModel.step` updates score/lifecycle state and calls `board.updateState(dt)`.
5. `Board.updateState` delegates to the configured `PhysicsStepper`.
6. The physics stepper mutates the shared `Board` state.
7. The runner publishes a new immutable snapshot for the UI and bots.

The physics step itself is split into bounded internal sub-steps by
`maxStepMillis`. Large elapsed times are therefore processed as a loop of
smaller steps.

## Responsibilities

| Class | Responsibility |
| --- | --- |
| `GameModel` | Owns gameplay state above physics: scores, finished state, winner, elapsed time, and cue-ball readiness. |
| `Board` | Owns mutable ball entities, holes, bounds, and pending scoring events. It is the shared mutable physics state. |
| `PhysicsStepper` | Minimal strategy interface that lets `Board` delegate stepping to one of several physics implementations. |
| `PhysicsEngine` | Sequential reference stepper. Integrates balls, applies holes, detects collisions, and resolves them in one thread. |
| `ThreadedPhysicsEngine` | Worker-thread stepper with long-lived platform threads and monitor-based task assignment. |
| `TaskBasedPhysicsEngine` | Executor-based stepper with a fixed pool and per-phase task submission. |
| `PhysicsWorker` | Reused worker thread for the platform-thread implementation. |
| `WorkerCompletionMonitor` | Per-phase barrier used by the controller thread to wait for all assigned workers. |
| `ThreadedGameRunner` / `TaskBasedGameRunner` | Own the `GameModel` and serialize command execution, stepping, and snapshot publication on one controller thread. |

## Current Sequential Baseline

The baseline path is `GameModel -> Board -> PhysicsEngine`.

- `GameModel.step` is synchronized and owns gameplay bookkeeping.
- `Board.updateState` is synchronized and delegates to the physics strategy.
- `PhysicsEngine.step` synchronizes on the board again, then performs the
  entire tick on the caller thread.
- One sub-step does:
  - integrate player cue ball, bot cue ball, and all small balls;
  - apply hole interactions;
  - build a broad-phase collision grid;
  - generate and sort candidate pairs;
  - resolve collisions immediately and deterministically.

This is the cleanest correctness baseline, but it has no intra-tick parallelism.

## Current Thread-Based Behavior

The platform-thread engine keeps the same single-writer board ownership rule,
but it parallelizes the expensive per-tick phases with long-lived workers.

Per sub-step:

1. The caller enters `ThreadedPhysicsEngine.step`.
2. The engine synchronizes on the `Board` for the whole tick.
3. It builds an active-ball list on the controller thread.
4. It assigns disjoint index ranges to pre-created `PhysicsWorker` threads.
5. A `WorkerCompletionMonitor` barrier waits for all workers to finish.
6. Hole handling is still sequential on the controller thread.
7. Collision detection builds per-worker spatial grids in parallel.
8. The controller merges the local grids, deduplicates candidate pairs, and
   sorts them.
9. Collision resolution is parallelized again by accumulating per-worker
   impulse deltas, then merging and applying the totals on the controller
   thread.

Important properties:

- Worker threads are created once in the engine constructor and reused across
  ticks.
- Tasks are recreated every phase, but the underlying threads are long-lived.
- The controller thread remains the merge point for all shared-state updates.

## Current Executor-Based Behavior

The task-based engine keeps the same board ownership rule, but it replaces
explicit workers with a fixed `ExecutorService`.

Per sub-step:

1. The caller enters `TaskBasedPhysicsEngine.step`.
2. The engine synchronizes on the `Board` for the whole tick.
3. It builds `ActiveBall` wrappers on the coordinator thread.
4. It submits range tasks to the executor for integration.
5. It submits range tasks again for hole detection and merges the per-task
   results on the coordinator thread.
6. It builds one local spatial grid per task, merges those grids, and
   generates deterministic candidate pairs.
7. For small collision sets, it resolves rounds directly with executor tasks.
8. For large collision sets, it switches to the accumulated-impulse solver,
   which again uses executor tasks for per-range accumulation and final
   application.

Important properties:

- Pool threads are reused, but `Future`s and task lambdas are recreated for
  every phase.
- Some tiny phases run inline when the range count collapses to one.
- The coordinator still performs all merges, ordering, and final board writes.

## Likely Sources Of Overhead

- `GameModel.step` and all public `GameModel` accessors are synchronized.
- `Board` methods are synchronized, so readers and physics updates contend on
  the same monitor.
- Every tick publishes new snapshots, which means repeated board reads and
  repeated immutable wrapper allocation.
- `Board.getBalls()` allocates a fresh snapshot list every time it is called.
- `Board.getHoles()` allocates a fresh copy every time it is called.
- `ThreadedGameSnapshot.from` and `TaskBasedGameSnapshot.from` copy those
  lists again with `List.copyOf`.
- The threaded engine allocates a `WorkerCompletionMonitor` for every `runRanges`
  call and creates new per-worker accumulator objects per collision phase.
- The executor engine allocates new `Future`s, task lambdas, range chunks, and
  result containers per phase.

## Likely Hidden Serialization Points

- The `Board` monitor is the main hidden serialization point: even though
  workers compute in parallel, all physics still happens under one board lock.
- `GameModel` is another serialization point because the controller thread and
  readers call synchronized methods for shots, previews, snapshots, and steps.
- `ThreadedGameRunner` and `TaskBasedGameRunner` each have one controller
  thread that serializes command draining, stepping, and snapshot publication.
- In the task-based runner, `ScheduledExecutorService` is single-threaded, so
  ticks are still serialized even though the physics inside a tick is parallel.
- In the threaded engine, the controller waits at every barrier; that waiting
  becomes a hard synchronization point even when the worker code is otherwise
  independent.

## What Is Actually Parallel

Only the work over disjoint ball or pair ranges is parallel:

- ball integration
- local spatial-grid construction
- collision contribution accumulation
- final per-ball delta application in the threaded and accumulated task-based
  solvers

Everything else remains sequential:

- command draining
- `GameModel` bookkeeping
- hole aggregation in the sequential engine
- grid merge
- candidate-pair deduplication and ordering
- final board mutation and scoring merge

## Risks If The Design Is Simplified

- Removing synchronization around `Board` or `GameModel` would likely expose
  torn reads, lost score updates, or races between rendering and physics.
- Parallelizing collision resolution without deterministic merge rules could
  change the simulation outcome from run to run.
- Reusing accumulators without a full reset would leak deltas across ticks.
- Letting readers observe mutable `Ball` instances directly would break the
  snapshot contract used by the GUI and benchmarks.
- Changing the order of pair collection or hole scoring could alter who gets
  credit for a pocketed ball, which is a correctness issue rather than a mere
  performance tweak.

## Bottom Line

The architecture already has a clear single-writer model:

- `GameModel` owns game rules.
- `Board` owns the mutable physics state.
- The runner owns the controller thread.
- The threaded and task-based engines only parallelize disjoint computation
  inside one synchronized tick.

That makes the code reasonably safe, but it also means the practical
parallelism is narrower than the class names suggest. The biggest costs are the
board/game synchronization boundary, per-tick snapshot copying, and the merge
steps that turn parallel work back into one serialized result.
