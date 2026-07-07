# Assignment 1 Physics Architecture Analysis

This document traces one simulation tick through the current Assignment 1
physics pipeline and identifies where work is sequential, parallel, copied,
merged, or serialized.

It is written as an audit note for the oral discussion: the goal is to explain
what is actually parallelized in the implementation, what still runs under a
single-writer rule, and where Amdahl bottlenecks remain.

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

The analysis focuses on the code path that is actually executed by the current
runtime. When a helper exists in the class but is not called by the live tick
path, it is treated as supporting code rather than as effective parallelism.

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

## Executive Summary

The current design is intentionally conservative:

- the game and physics state have a single writer at any point in time;
- the expensive numeric parts of the tick are parallelized;
- the merge/commit phases remain serialized for determinism and scoring
  correctness;
- the task-based and threaded engines both use explicit barriers to separate
  worker computation from coordinator commits.

So the answer to "is everything parallelized?" is no. What is parallelized is
the computation over disjoint ranges or private accumulators. What remains
serial is the commit onto the shared `Board`, the game-rule bookkeeping, and
the ordering/merge steps that restore determinism.

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

### What runs sequentially here

In the sequential engine, all of the following happen on the same thread:

- movement integration for all active balls;
- hole interaction handling;
- spatial-grid broad-phase construction;
- collision-pair generation and deduplication;
- collision resolution and board mutation.

This makes the sequential engine the reference semantics for correctness and
for comparing benchmark results.

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

### Threaded phase breakdown

The threaded engine parallelizes:

- integration over disjoint active-ball ranges;
- local grid construction for collision broad-phase;
- collision contribution computation for canonically owned cells;
- final delta application only when the touched set is large enough.

The threaded engine keeps the following steps serial:

- acquiring and holding the board monitor for the whole tick;
- `board.applyHoleInteractions()`;
- merging local grids into a global deterministic grid;
- sorting cells and encoded candidate pairs;
- `board.recordCollision(...)` writes;
- merging per-worker delta accumulators;
- any small final apply phase that falls below the parallel threshold.

### Why the barriers exist

The worker barrier is not an implementation accident. It is the mechanism that
guarantees:

- all workers observe the same tick-start state;
- the coordinator can merge without races;
- collision ordering remains deterministic;
- scoring and pocket removal happen after the physics computations are done.

That barrier is therefore a correctness boundary, not just a performance
detail.

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

### Task-based phase breakdown

The task-based engine parallelizes:

- movement integration over disjoint ball ranges;
- broad-phase local-grid construction;
- collision resolution over owned cell ranges;
- final apply of sparse deltas when enough balls were touched.

The task-based engine keeps the following steps serial:

- the `synchronized (board)` section that spans the whole tick;
- the coordinator-side merge of local per-task grids;
- ordering of cells and packed pairs;
- `board.applyHoleInteractions()` as currently used in the live tick path;
- `board.recordCollision(...)` and final commit of aggregated results;
- any tiny phase that collapses to a single range and therefore runs inline.

### Important nuance about helper methods

The task-based class also contains helper methods such as
`detectCollisionPairsPacked(...)`, `resolveCollisionsInParallelRounds(...)`,
and the accumulated-impulse solver.

In the current codebase, these helpers exist as supporting logic and testable
building blocks, but the live tick path executed by `stepOnce(...)` goes
through `detectAndResolveCollisions(...)`. So, for the purpose of the runtime
audit, the parallelism that counts is the one reached from that path.

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

## Phase By Phase Audit

This is the most useful view for the oral discussion.

| Phase | Threaded engine | Task-based engine | Why not fully parallel |
| --- | --- | --- | --- |
| Tick entry and sub-step loop | serial | serial | one writer owns the board for the whole tick |
| Active-ball collection | serial | serial | must read shared board state before workers start |
| Movement integration | parallel over disjoint ranges | parallel over disjoint ranges | each worker writes to its own ball subset |
| Hole interactions | serial in the live path | serial in the live path | modifies shared scoring and pocket state on the board |
| Broad-phase local grid build | parallel | parallel | workers use private maps/buckets |
| Grid merge and ordering | serial | serial | coordinator must deduplicate and sort deterministically |
| Collision contribution computation | parallel over cells | parallel over cells | each worker owns a disjoint set of cells |
| Collision pair recording | serial | serial | writes to shared board bookkeeping |
| Delta merge | serial | serial | combines private accumulators into one authoritative result |
| Final delta application | parallel only above threshold | parallel only above threshold | below threshold, parallel overhead is worse than the work |
| Snapshot publication / game bookkeeping | serial | serial | belongs to controller/runners, not worker phases |

## Shared State And Synchronization Points

The main shared objects are:

- `Board`, which owns mutable balls, holes, pocket flags, and scoring state;
- `GameModel`, which owns score, status, elapsed-time, and shot readiness;
- the runner controller, which serializes command handling and snapshot
  publication;
- worker completion barriers, which separate computation from merge/commit.

The main synchronization points are:

1. `synchronized (board)` in the physics engines.
2. `board.updateState(...)` in `GameModel.step(...)`.
3. `WorkerCompletionMonitor.await()` in the threaded engine.
4. `Future.get()` in the task-based engine.
5. `GameModel.step(...)`, `GameModel.snapshot()`, and the runner tick loops.

These points are all intentionally coarse-grained. The design prefers a small
number of explicit barriers over a more fragile fine-grained lock scheme.

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

## Amdahl Bottlenecks

The main Amdahl bottlenecks are not accidental bugs; they are the unavoidable
serial sections left by the current design.

### 1. Board ownership

The whole physics tick runs under one `synchronized (board)` block. This means
the board cannot be mutated concurrently by another physics step or by a
command that would alter the same mutable state.

This is the strongest correctness boundary in the design, but also the biggest
cap on total speedup.

### 2. Merge and ordering

Even when worker computation is parallel, the coordinator still has to:

- merge local grids or deltas;
- sort cells or candidate pairs;
- apply the final board mutations in a deterministic order.

These serial sections grow with the number of occupied cells, the number of
contacts, and the number of touched balls.

### 3. Hole handling and scoring

Hole resolution is deliberately centralized in `Board.applyHoleInteractions()`
or its commit-style variant.

That keeps pocket removal and score bookkeeping consistent with the mutable
board list, but it also means that the hole phase is not a parallel hot spot.

### 4. Small workloads

The task-based engine explicitly avoids parallelization when the work is too
small:

- `MIN_ITEMS_PER_PARALLEL_TASK` prevents tiny range tasks;
- `MIN_TOUCHED_BALLS_FOR_PARALLEL_APPLY` keeps small apply phases serial.

That is a practical Amdahl tradeoff: some phases are faster if they are not
parallelized at all.

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

For the report, the short version is:

- parallelism is real, but it is phase-local rather than end-to-end;
- the physics math is parallelized more than the game rules;
- correctness and determinism are protected by a single-writer architecture;
- the remaining serial sections are the expected Amdahl bottlenecks of the
  current design.
