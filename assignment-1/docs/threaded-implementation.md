# Thread-Based Implementation

## 1. Objective
The thread-based version is the platform-thread execution strategy of Poool.
It does not introduce a separate game model and it does not redefine the game
rules. Instead, it preserves the sequential implementation as the semantic
baseline and changes how each tick is executed.

The current implementation is designed to achieve the following goals:

- asynchronous command submission from GUI and bot logic;
- responsive rendering through immutable snapshots;
- clear ownership of the mutable game state;
- deterministic behavior despite internal parallelism;
- effective use of multiple CPU cores, especially on large boards;
- lower collision-management overhead than the previous threaded pipeline.

## 2. Main Runtime Components
The playable entry point is `pcd.poool.ThreadedPoool`.

The active runtime components are:

- `ThreadedPoool`
  Swing-facing launcher and render loop.
- `ThreadedGameRunner`
  runtime coordinator for the thread-based version.
- `poool-threaded-controller`
  platform thread that owns command execution, game stepping, rule progression,
  and snapshot publication.
- `poool-threaded-bot`
  optional platform thread that observes snapshots and submits bot-shot
  commands asynchronously.
- `poool-physics-worker-*`
  long-lived platform threads owned by `ThreadedPhysicsEngine`.
- Swing EDT
  event-dispatch thread that receives keyboard and mouse events and turns them
  into commands.

The resulting communication structure is:

```text
Swing EDT / BotThread
        |
        v
 CommandQueueMonitorSupport
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
 SnapshotStoreSupport
        |
        v
 GUI render loop / BotThread
```

## 3. State Ownership
The central correctness rule is single-writer ownership of the authoritative
game state.

- `GameModel` remains the owner of game rules: score, cue-ball availability,
  lifecycle state, and termination.
- `Board` remains the owner of the mutable physical entities and low-level
  physics events.
- Only the controller thread invokes mutating operations on `GameModel`.
- GUI and bot threads never mutate `Board` or `GameModel` directly.
- Physics workers run only as internal helpers of one controller-owned physics
  step.

This design avoids per-ball locking, nested lock ordering issues, and race
conditions caused by letting multiple external threads mutate adjacent balls or
board regions independently.

## 4. Command-Based Coordination
Player input and bot actions are represented as asynchronous commands.

`CommandQueueMonitorSupport` is the monitor used by producer threads. It accepts
commands from the Swing EDT and the bot thread, while the controller thread
drains and executes them in FIFO order.

Each submitted shot returns a `CommandReceiptSupport<Boolean>`. The receipt is useful
for tests and for any caller that needs to wait until the controller has either
accepted or rejected the command.

On shutdown, the command monitor rejects all queued but unexecuted commands.
This ensures that no caller remains blocked forever waiting for a result that
can no longer be produced.

## 5. Snapshot-Based Rendering
Rendering is based on immutable snapshots rather than direct access to mutable
physics objects.

`SnapshotStoreSupport` stores the latest `RuntimeGameSnapshot`. The GUI reads this
snapshot and copies its contents into `ViewModel`. The snapshot contains:

- logical game state;
- small-ball render snapshots;
- human cue-ball render snapshot;
- bot cue-ball render snapshot;
- hole layout;
- bot shot preview vector.

The GUI never reads mutable `Ball` instances directly. This keeps Swing
rendering independent from concurrent physics mutation and avoids UI/model
races.

## 6. Structure of the Current Threaded Physics Engine
The computationally expensive part of the thread-based implementation is
`ThreadedPhysicsEngine`. It implements the same `PhysicsStepper` interface as
the sequential `PhysicsEngine`, so `GameModel` can be reused unchanged at the
game-rule level.

For each physics tick, the controller eventually calls:

```text
board.updateState(dt)
```

The board delegates the step to the injected `ThreadedPhysicsEngine`. The
engine synchronizes on the board, splits large elapsed times into bounded
sub-steps, and executes each sub-step through a staged worker-based pipeline.

The current sub-step pipeline is:

```text
1. collect active balls
2. integrate movement in parallel
3. apply hole interactions serially
4. collect collision balls
5. build local center-cell grids in parallel
6. merge local grids serially
7. sort populated cells deterministically
8. resolve owned cell and neighbor collisions in parallel
9. merge sparse collision accumulators
10. record collisions and apply final deltas
```

The most important difference from the previous threaded approach is that the
current engine no longer builds one large global list of deduplicated
candidate pairs. Instead, it assigns collision work by spatial cell ownership
and performs contact detection and contact-resolution contribution generation
locally inside each worker.

### 6.1 Parallel Ball Integration
At the beginning of a sub-step, the engine builds the list of active balls:

- human cue ball, if present;
- bot cue ball, if present;
- all remaining small balls.

This list is divided into contiguous chunks by index. Each `PhysicsWorker`
receives one chunk and updates every assigned ball independently by applying:

- friction;
- position integration;
- boundary constraint handling.

This phase scales well because each ball can be updated without reading or
writing any other ball.

### 6.2 Serial Hole Interaction Phase
After integration, the controller invokes `board.applyHoleInteractions()`.

This phase remains serial because it directly mutates the authoritative board
state:

- cue-ball pocketed flags;
- small-ball removal from the board;
- low-level scoring-related events tied to pocketing.

Keeping this step serial is consistent with the single-writer ownership rule
and avoids exposing partial pocketing decisions to worker threads.

### 6.3 Spatial Broad Phase Based on Center Cells
After movement and hole handling, the engine collects the balls that are still
eligible for collision checks through `board.getCollisionBalls()`.

The broad phase uses a uniform grid, but the current engine stores each ball
only in the grid cell containing its center. This is deliberately lighter than
the older approach that registered a ball in every cell overlapped by its
radius.

The process is:

1. the engine computes a cell size based on the maximum radius currently
   present among the collision balls;
2. each worker builds a local map `GridCell -> ball indexes` for the ball range
   assigned to it;
3. the controller merges the local maps into one global grid;
4. the populated cells are sorted deterministically.

Using only the center cell reduces:

- duplication of ball indexes across many cells;
- memory pressure in dense boards;
- the amount of pair bookkeeping that the old broad phase had to merge.

### 6.4 Cell Ownership and Forward Neighbor Scanning
Once the global grid has been built, the populated cells are partitioned across
workers. Each worker becomes responsible for a subset of cells.

For each owned cell, the worker checks:

- collisions among the balls inside the same cell;
- collisions against balls contained in a small set of forward neighbors:
  - right;
  - up;
  - up-right diagonal;
  - down-right diagonal.

This pattern is important because it guarantees that each cross-cell pair is
considered exactly once. There is therefore no need to build a giant global
candidate-pair set and no need for an expensive final deduplication pass.

Conceptually, the collision phase is now:

```text
owned cell
  -> internal pairs
  -> pairs with selected forward neighbors
  -> exact collision test
  -> local contribution accumulation
```

The old threaded engine paid a large overhead in:

- creating candidate pairs globally;
- deduplicating those pairs;
- sorting them before resolution;
- merging large dense per-ball delta structures.

The current engine attacks exactly that bottleneck by fusing broad-phase
locality and collision-work ownership.

### 6.5 Parallel Collision Contribution Computation
When a worker finds a potentially relevant pair, it performs the exact overlap
test using the actual ball positions and radii. If the two balls do not
overlap, nothing is produced.

If the pair is a real contact, the worker computes:

- the contact normal;
- the overlap correction to separate the balls;
- the elastic impulse along the collision normal;
- the position and velocity deltas for both balls.

Crucially, workers do not modify the `Ball` objects immediately. They only
produce `CollisionContribution` records and accumulate them locally.

This means that the expensive numerical part of collision resolution is
parallel, while the authoritative board state remains untouched until the
aggregation phase.

### 6.6 Sparse Per-Worker Delta Accumulators
Each worker stores collision results in a sparse local accumulator.

Unlike the previous approach, which relied on large dense arrays covering all
balls, the current accumulator explicitly tracks only the ball indexes that were
actually touched by at least one real collision.

For each touched ball, the accumulator stores:

- accumulated position delta on `x`;
- accumulated position delta on `y`;
- accumulated velocity delta on `x`;
- accumulated velocity delta on `y`.

This sparse strategy reduces overhead when:

- only a subset of the board is colliding;
- collisions are spatially clustered;
- the total number of balls is large but the number of contacts per tick is
  much smaller.

### 6.7 Merge and Final Apply Phase
Once all workers complete their assigned cells, the controller:

1. merges the sparse local accumulators;
2. merges the lists of real collision pairs;
3. sorts the collision-pair list deterministically;
4. invokes `board.recordCollision(...)` for each real contact;
5. applies the final accumulated deltas to the touched balls.

The final apply phase is adaptive:

- if the number of touched balls is small, the apply is done sequentially;
- if the number of touched balls is large enough, the apply is itself
  parallelized by ball range.

This preserves correctness while avoiding unnecessary coordination on tiny
workloads.

### 6.8 Why This Parallelization Is Safe
The current design avoids concurrent writes to shared `Ball` objects during the
expensive collision-computation stage.

Workers only:

- read the tick-start state of balls for the pair they are processing;
- write to worker-private accumulators.

The authoritative mutations happen later, in controlled phases:

- the controller records low-level collision events on the board;
- the final accumulated deltas are applied once the worker phase has ended;
- if that apply is parallelized, each touched ball index is assigned to exactly
  one worker.

As a consequence, the engine does not need per-ball locks. Synchronization is
coarse but explicit:

- one barrier after each worker phase;
- one deterministic merge by the controller;
- one controlled final apply.

### 6.9 Relation to the Sequential Baseline
The benchmark speedup values are always computed with respect to the sequential
engine:

```text
speedup = sequential_time / threaded_time
```

The current threaded engine is therefore not judged against its previous
versions but directly against the sequential baseline, which remains the
reference implementation for semantics and performance comparison.

From a semantic point of view, the current threaded engine still aims to
preserve the same overall gameplay behavior as the sequential version, even if
its internal collision pipeline is structured differently to expose more useful
parallel work.

## 7. Worker Lifecycle
`ThreadedPhysicsEngine` creates long-lived platform threads once. Workers are
not created at every tick.

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

Workers are closed only after the controller thread has stopped, so they are
not interrupted while the controller is waiting for an active physics phase.

## 8. Summary
The current thread-based version is organized around a simple idea: the game
logic remains centralized in one controller thread, while the heavy numerical
work of physics is delegated to long-lived worker threads.

The most important aspect of the current implementation is the collision
pipeline. Instead of relying on a large global set of candidate pairs, the
engine:

- partitions work through spatial cells;
- scans only local and forward-neighbor regions;
- computes collision contributions in parallel;
- accumulates only the touched-ball deltas;
- performs a deterministic final merge and apply.

This architecture keeps the model understandable and safe, while making the
thread-based version significantly more competitive on large workloads than the
earlier threaded design.

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
parallel contribution computation, deterministic aggregate commit
```

This gives a strong balance between performance and correctness:

- worker threads compute expensive physics and collision-contribution phases;
- the controller thread preserves model ownership;
- snapshots decouple simulation from rendering;
- custom monitors coordinate commands, snapshots, and worker completion;
- the sequential game rules remain the semantic baseline.
