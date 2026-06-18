# Thread-Based Implementation

## 1. Objective
The thread-based version is the only multithreaded implementation of Poool.
It is not a second game model and it is not a fork of the sequential rules.
Instead, it preserves the sequential implementation as the semantic baseline
and changes the execution strategy around it.

The implementation targets the following goals:

- asynchronous player input;
- asynchronous bot behaviour;
- responsive Swing rendering;
- stable model ownership without data races;
- effective use of multiple CPU cores on large boards;
- deterministic and explainable physics results.

## 2. Main Runtime Components
The playable entry point is `pcd.poool.ThreadedPoool`.

The active components are:

- `ThreadedPoool`: GUI/render loop. It reads immutable snapshots and updates
  `ViewModel`.
- `ThreadedGameRunner`: lifecycle coordinator for the multithreaded runtime.
- `poool-threaded-controller`: platform thread that owns command execution,
  game stepping, game-rule progression, and snapshot publication.
- `poool-threaded-bot`: optional platform thread that observes snapshots and
  submits bot-shot commands asynchronously.
- `poool-physics-worker-*`: long-lived platform threads owned by
  `ThreadedPhysicsEngine`.
- Swing EDT: receives local keyboard/mouse events and produces commands.

The resulting communication structure is:

```text
Swing EDT / BotThread
        |
        v
 CommandQueueMonitor
        |
        v
 Controller platform thread
        |
        v
 GameModel + Board + ThreadedPhysicsEngine
        |
        v
 PhysicsWorker[]
        |
        v
 SnapshotStore
        |
        v
 GUI render loop / BotThread
```

## 3. State Ownership
The central correctness rule is single-writer ownership of the authoritative
game state.

- `GameModel` remains the owner of game rules: score, cue-ball
  availability, status, and termination.
- `Board` remains the owner of physical entities and low-level physics events.
- Only the controller thread invokes mutating operations on `GameModel`.
- GUI and bot threads never mutate `Board` or `GameModel` directly.
- Physics workers only execute assigned physics computations during a
  controller-owned step.

This structure avoids per-ball locks and prevents deadlocks caused by multiple
threads trying to acquire locks on neighbouring balls or board regions.

## 4. Command-Based Coordination
Player input and bot actions are represented as asynchronous commands.

`CommandQueueMonitor` is a custom monitor used by producer threads. It accepts
commands from the Swing EDT and bot thread, while the controller drains and
executes them in FIFO order.

Each submitted shot returns a `CommandReceipt<Boolean>`. The receipt is useful
for tests and for any caller that needs to wait until the controller has either
accepted or rejected a command.

On shutdown, the command monitor rejects all queued but unexecuted commands.
This prevents callers from waiting forever on a command receipt.

## 5. Snapshot-Based Rendering
Rendering uses immutable snapshots.

`SnapshotStore` stores the latest `ThreadedGameSnapshot`. The GUI reads this
snapshot and copies it into `ViewModel`. The snapshot contains:

- logical game state;
- small-ball render snapshots;
- human cue-ball render snapshot;
- bot cue-ball render snapshot;
- hole layout;
- bot shot preview vector.

The GUI does not read mutable `Ball` instances. This keeps Swing rendering
independent from physics mutation and avoids UI/model races.

## 6. Worker-Based Physics Pipeline
The heavy part of the thread-based implementation is `ThreadedPhysicsEngine`.
It implements the same `PhysicsStepper` strategy interface as the sequential
`PhysicsEngine`, so `GameModel` can be reused unchanged at the game-rule
level.

For each physics tick, the controller calls:

```text
board.updateState(dt)
```

The board delegates to the injected `ThreadedPhysicsEngine`. The engine then
executes bounded sub-steps. Each sub-step follows this pipeline:

```text
1. collect active balls
2. integrate ball chunks in parallel
3. apply hole interactions serially
4. collect collision balls
5. build local spatial grids in parallel
6. merge local grids serially
7. deduplicate and sort candidate pairs
8. resolve collisions serially in stable order
9. return control to game-rule logic
```

### 6.1 Parallel Integration
The active balls are divided into contiguous chunks. Each `PhysicsWorker`
updates one chunk:

- friction;
- position integration;
- boundary bounce handling.

This phase scales well because each ball can be integrated independently.

### 6.2 Parallel Broad Phase
Collision detection uses a uniform spatial grid. Each worker builds a local
map from grid cells to ball indexes for its assigned range.

The controller then merges the local maps into a global grid. Candidate pairs
are generated from balls that share at least one cell. Each candidate is stored
as an ordered pair `(minIndex, maxIndex)`, deduplicated in a set, and finally
sorted by stable indexes.

This avoids the quadratic all-against-all collision check and is the key to
handling thousands of balls.

### 6.3 Serial Collision Resolution
Collision resolution remains serial and deterministic.

This is intentional. Resolving collisions mutates velocities and positions of
two balls at a time. Parallelizing this phase safely would require either:

- locking individual balls;
- graph colouring / independent-pair batching;
- or speculative resolution with rollback.

Those options add significant complexity and make verification harder. The
current design therefore parallelizes the high-volume independent phases and
serializes the conflict-prone commit phase.

## 7. Worker Lifecycle
`ThreadedPhysicsEngine` creates long-lived platform threads once. Workers are
not created for every tick.

The controller assigns range tasks to workers and waits on
`WorkerCompletionMonitor`. The monitor tracks how many workers still need to
finish the current phase and propagates worker failures back to the controller.

The lifecycle is:

```text
ThreadedGameRunner.start()
  -> creates ThreadedPhysicsEngine
  -> starts controller and bot threads
  -> physics engine owns worker threads

ThreadedGameRunner.close()
  -> requests controller/bot shutdown
  -> joins controller/bot
  -> closes ThreadedPhysicsEngine workers
```

Workers are closed after the controller thread has stopped, so they are not
interrupted while the controller is waiting for an active physics phase.

## 8. Scalability Rationale
The threaded implementation is designed for configurations with hundreds or
thousands of balls.

The expected speedup comes from:

- distributing integration over multiple workers;
- distributing spatial-grid construction over multiple workers;
- avoiding quadratic collision detection through spatial partitioning;
- keeping rendering independent from simulation mutation.

The implementation should perform best when balls are spatially distributed.
If almost all balls occupy the same small region, the grid naturally produces
many candidate pairs and the serial merge/resolution phases become more
dominant.

## 9. Commands
Build and run tests:

```bash
mvn -f assignment-1/pom.xml test
```

Run the playable multithreaded version:

```bash
java -cp assignment-1/target/classes pcd.poool.ThreadedPoool
```

To test different playable board sizes, change `BOARD_PROFILE` in
`pcd.poool.ThreadedPoool`:

```java
private static final BoardProfile BOARD_PROFILE = BoardProfile.THOUSAND;
```

Available profiles are `STANDARD`, `THOUSAND`, and `MASSIVE`.

Run the sequential physics benchmark:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.PhysicsBenchmark 600
```

Run the threaded physics benchmark:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.ThreadedPhysicsBenchmark 600
```

Optionally specify worker count:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.ThreadedPhysicsBenchmark 600 8
```

## 10. Exam Summary
The thread-based version exploits multiple cores by parallelizing the
independent phases of the physics simulation while keeping game-rule mutation
serialized.

The key design decision is:

```text
parallel computation, deterministic serial commit
```

This gives a strong balance between performance and correctness:

- worker threads compute expensive physics phases;
- the controller thread preserves model ownership;
- snapshots decouple simulation from rendering;
- custom monitors coordinate commands, snapshots, and worker completion;
- the sequential game rules remain the semantic baseline.
