# Assignment 1 - Concurrent Analysis and Architectural Design (Poool)

## 1. Goal and scope
Define a concurrent architecture for Poool that is correct, responsive, and verifiable.

Includes: game logic, physics simulation, user input, bot, GUI.
Excludes: multiplayer networking, persistence, hardware-specific optimizations.

## 2. Reused artifacts from sketch-01 and sketch-02
In this context, "reused" means concrete source artifacts (files/classes), not only ideas.

Final-delivery code under `pcd.poool` reuses mainly:
- from `pcd.sketch01`: physics/domain + board view artifacts (`Ball`, `Board`, `BoardConf`, board configs, `Boundary`, `P2d`, `V2d`, `ViewModel`, `View`, `ViewFrame`, `RenderSynch`)
- from `pcd.sketch02`: concurrency/controller pattern artifacts (`Cmd`, `ActiveController`, `BoundedBuffer`, `BoundedBufferImpl`)

Non-final demo artifacts remain under `assignment-1/reference/sketch01` and `assignment-1/reference/sketch02`; they are not part of the Maven build or the `pcd.poool` final-delivery scope.

Conceptual baseline from `sketch-01`:
- main loop that updates board state and renders frames
- attention to concurrency between model updates and EDT painting
- possible use of double buffering

Conceptual baseline from `sketch-02`:
- MVC
- asynchronous input
- active controller with producer/consumer on a bounded buffer
- non-blocking variant (`poll`) when the controller must keep looping

## 3. Chosen architecture (primary)

### 3.1 Components and responsibilities
1. `GameController` (active)
- orchestrates the match
- manages state transitions (`WAITING_INPUT`, `SIMULATING`, `TURN_RESOLUTION`, `GAME_OVER`)
- coordinates input, bot, physics, and GUI

2. `PhysicsEngine` (passive service, executed by a runner/loop)
- computes deterministic physics steps when called
- handles movement, friction, wall bounces, holes, and collisions
- does not own a thread by itself

3. `InputHandler` (active/event-driven)
- acquires input and translates it into commands
- sends commands to the controller

4. `BotAgent` (active)
- generates a shot when it is the bot turn
- sends command to the controller

5. `GUIRenderer` (active)
- reads immutable snapshots and renders

6. `GameStateModel` (passive)
- turn, score, match state

7. `PhysicsStateModel` (passive)
- ball position/velocity/dynamic state

### 3.2 Main interactions
- `InputHandler -> GameController`
- `BotAgent -> GameController`
- `GameController -> PhysicsEngine`
- `PhysicsEngine -> GameController` (e.g., `balls_stopped`)
- `PhysicsEngine -> GUIRenderer` (snapshot)
- `GameController -> GUIRenderer` (logical state)

### 3.3 Command-based controller pattern
The current controller foundation is based on a combination of the Command
pattern and the Active Object pattern.

`Cmd<T>` represents a request to perform an action on a target object of type
`T`. The command stores the parameters of the action, but does not execute the
action immediately. Execution happens only when the active controller consumes
the command from its queue.

Example conceptual flow:

```text
keyboard input / bot decision / GUI event
        |
        v
      Cmd<T>
        |
        v
 ActiveController<T>
        |
        v
   target model/service
```

For example, a future `KickPlayerCmd` should not need to own or directly expose
the player ball. It can be a `Cmd<Board>` that stores the desired velocity and,
when executed, calls a board-level operation such as `board.kickPlayer(velocity)`.
The `Board` then applies the change to its internal `playerBall`.

This keeps the ownership clear:

- producers such as the input handler and the bot create commands
- the active controller serializes command execution
- model objects are modified through a controlled entry point
- the view observes model snapshots instead of driving game logic directly

The resulting path is:

```text
InputHandler/Bot/View event
        |
        v
      command
        |
        v
 controller queue
        |
        v
 ActiveController.run()
        |
        v
 command.execute(target)
        |
        v
 model state update
        |
        v
 ViewModel snapshot -> View rendering
```

Not every internal operation must be represented as a command. Internal model
logic, such as collision resolution inside `Ball` or physics integration inside
`Board`, can remain regular domain methods. Commands are mainly useful for
external asynchronous requests that may come from different threads, such as
user input, bot actions, reset, pause, resume, or turn-management events.

### 3.4 Physics execution strategy
`PhysicsEngine` is intentionally not a `Thread`. It is a synchronous,
deterministic service:

```text
physicsEngine.step(board, dt)
```

This keeps the physics computation reusable across all required assignment
variants. The execution strategy decides when and where the engine is called:

- sequential version: the main/game loop calls `PhysicsEngine.step(...)`
- platform-thread version: a dedicated physics loop thread may call it at fixed
  ticks
- task-based version: an `ExecutorService` may run physics steps or selected
  physics phases as tasks

`Cmd` is best suited for discrete asynchronous requests:

- kick
- reset
- pause/resume
- bot shot
- turn-management event

Physics stepping is different: it is a continuous periodic process. It could be
wrapped in a command such as `PhysicsStepCmd`, but that should be treated as an
execution choice, not as a domain requirement. The preferred separation is:

```text
Cmd = external/discrete request
PhysicsEngine = deterministic step computation
Runner/Loop = scheduling and threading policy
```

The important rule is ownership. The board/physics state must have a single
writer at any given time: either the sequential loop, a physics thread, or a
serialized task/controller. The system should avoid allowing arbitrary threads
to call `PhysicsEngine.step(...)` on the same board concurrently.

### 3.5 Preservation of the sequential baseline
The sequential version must remain a first-class implementation, not a
temporary prototype to be replaced by the concurrent variants. The concurrent
architecture should therefore be introduced as an alternative execution policy
around the same domain concepts, preserving as much reusable code as possible.

The following elements should remain shared across sequential, platform-thread,
and task-based versions:

- immutable mathematical value objects (`P2d`, `V2d`);
- board configuration classes and deterministic initial layouts;
- core physical entities (`Ball`, `Boundary`, `Hole`, `BoardConf`);
- collision semantics and numerical constants;
- game-rule concepts such as players, score, cue-ball loss, and game status;
- immutable snapshots used by the GUI and tests;
- benchmark configurations used to compare execution strategies.

The preferred separation is:

```text
Domain model and rules
  - what the game state means
  - what a legal shot is
  - how scores and termination are computed

Physics computation
  - how one deterministic step is calculated
  - how collisions, friction, walls, and holes are handled

Execution strategy
  - sequential loop
  - platform-thread runner
  - task-based runner
```

In this structure, the sequential runner remains the reference semantics. The
thread-based implementation should be validated against it whenever possible:
given the same deterministic initial board and the same sequence of accepted
commands, both implementations should produce equivalent logical outcomes. The
exact floating-point trajectory may diverge slightly if collision resolution is
parallelized, but scoring, termination, cue-ball availability, and remaining
ball counts should remain consistent.

This constraint also limits duplication. New concurrent classes should be
introduced mainly for scheduling, coordination, monitors, worker lifecycle, and
snapshot publication. They should not duplicate the whole game model or create
an independent set of physics rules. If a rule must change to support
concurrency, it should be extracted into a shared abstraction and tested once,
rather than reimplemented separately in each runner.

## 4. Synchronization and shared state

### 4.1 Ownership rules
- `GameStateModel`: single writer `GameController`
- `PhysicsStateModel`: single writer `PhysicsEngine`
- all other components read or send change requests through events/commands

### 4.2 Coordination strategy
- message passing by default
- thread-safe queue for events/commands
- short critical sections only
- no rendering under locks
- in Swing, avoid races between model updates and EDT repaint

## 5. Concurrent game loop
- Physics loop (fixed tick, e.g., 60 Hz): integration -> collisions -> snapshot publish -> rest check
- Control loop: consumes events, updates turn state, enables input or bot
- Rendering loop: reads latest available snapshot without blocking

### 5.1 Proposed multithreaded runtime
The preferred platform-thread design is based on a small number of active
components with explicit ownership, plus worker threads for the expensive
physics phases.

```text
Swing EDT
  - collects mouse/keyboard input
  - sends commands such as ShootHuman, Restart, Pause
  - never mutates the game model directly

BotThread
  - reads the latest immutable snapshot
  - computes a bot shot when the bot can play
  - sends ShootBot commands

GameControllerThread
  - owns logical game progression
  - consumes pending commands
  - coordinates physics steps
  - applies score, game-over, restart, and pause rules
  - publishes immutable snapshots for the GUI

PhysicsWorkerThread[]
  - process independent physics work, such as ball chunks or spatial regions
  - do not decide game rules
  - return partial results to the controller/physics coordinator

SnapshotStoreSupport
  - stores the latest immutable game/board snapshot
  - can be read by the GUI without blocking the simulation loop
```

The scoring and rule logic should not run as a fully independent writer thread:
it depends on physics events such as collisions, pocketed balls, and cue-ball
losses. For this reason, the `GameControllerThread` should apply rules after
each physics step, using the events produced by the physics layer.

Conceptual control flow:

```text
Input/Bot
   -> command queue
   -> GameControllerThread
   -> parallel physics step
   -> consume physics events
   -> update score/status
   -> publish snapshot
   -> View
```

## 6. Physics parallelization strategy
Strategy:
1. broad phase with spatial partitioning
2. merge/deduplicate candidate pairs
3. deterministic ordering of candidate pairs
4. parallel resolution of independent collision rounds

An intuitive platform-thread variant is to divide the board into spatial
regions, for example four quadrants, and assign each region to a worker thread.
Each worker processes the balls currently belonging to its region and produces
local movement/collision candidates. The coordinator then merges the partial
results, handles balls near region boundaries, deduplicates cross-region
collision pairs, and applies the final resolution in a deterministic order.

Region boundaries require an overlap/border policy. A worker must not consider
only the balls whose centers are inside its region: balls close to a border can
collide with balls owned by the adjacent region. For this reason, each region
also inspects a border band at least as wide as the maximum collision distance
used by the broad phase. Candidate pairs that cross a region boundary are
reported to the coordinator together with local pairs. Since the same pair may
be discovered by multiple workers, the merge phase stores pairs in a set and
then sorts them by stable ball/index identifiers before contribution
computation.

For large configurations, a uniform grid is preferable to hard-coded quadrants:
it generalizes to more workers, adapts better to thousands of balls, and
matches the existing broad-phase collision detector. Quadrants remain a useful
conceptual explanation for the report and for a first platform-thread design.

Tradeoffs:
- higher throughput
- higher synchronization complexity
- strong determinism requires explicit stable ordering

### 6.1 Feasibility assessment for the platform-thread version
The current code base already contains the main prerequisites for a robust
thread-based implementation:

- `PhysicsEngine` is a passive deterministic service, therefore the execution
  policy can be changed without embedding thread ownership inside the physics
  model.
- `Board` exposes synchronized operations and immutable rendering snapshots,
  which provides a clear starting point for enforcing a single-writer model.
- `SpatialCollisionDetector` already uses a deterministic uniform grid, making
  spatial decomposition a natural extension rather than a new conceptual
  subsystem.
- `GameModel` centralizes scoring, cue-ball availability, and termination
  rules, so the concurrent version can preserve the same game semantics by
  reusing the same rule order after each physics step.

The implementation is therefore feasible, but the most appropriate design is
not to let each worker thread mutate a portion of the board independently.
Such a design would make collisions across region boundaries difficult to
reason about, since a ball near a border may be affected by entities owned by
another thread. It would also increase the probability of deadlocks if locks
were acquired on multiple balls or regions during collision resolution.

The recommended architecture is instead a staged parallel algorithm: workers
perform computationally expensive, read-mostly phases on a stable view of the
physical state, while the coordinator applies the resulting state transitions
in a controlled deterministic phase. This approach preserves correctness and
modularity while still exploiting multiple CPU cores.

### 6.2 Proposed staged physics step
A platform-thread physics step should be decomposed into explicit barriers:

```text
GameControllerThread
  |
  |-- drain command queue
  |-- create physics snapshot / stable work view
  |-- start phase A on PhysicsWorkerThread[]
  |       integration candidates
  |-- barrier
  |-- start phase B on PhysicsWorkerThread[]
  |       spatial-grid buckets and collision candidates
  |-- barrier
  |-- merge and order candidate pairs
  |-- group ordered collision pairs into non-conflicting rounds
  |-- for each round:
  |       start phase C on PhysicsWorkerThread[] / Executor tasks
  |           resolve disjoint collisions in parallel
  |       barrier
  |-- resolve hole interactions
  |-- apply game rules and scoring
  |-- publish immutable snapshot
```

The task-based implementation uses this round-based variant for small contact
sets. For larger contact sets, where per-round task overhead would dominate, it
switches to a task-based accumulated-impulse solver: each task computes
position and velocity deltas in private arrays, the coordinator merges those
arrays deterministically, and a final task phase applies disjoint ball ranges.
This keeps the implementation based on the Executor Framework while making the
expensive impulse/displacement computation parallel under load.

The implemented threaded engine follows this staged idea. It does not let
workers mutate colliding balls while they inspect candidate pairs. Instead, each
worker owns a private collision accumulator with per-ball delta arrays:

```text
positionDeltaX[], positionDeltaY[], velocityDeltaX[], velocityDeltaY[]
```

For every exact overlapping contact, the worker computes the contact normal,
the overlap correction, and the elastic impulse contribution. Those values are
added to the private arrays under the indexes of the two involved balls. A ball
with several simultaneous contacts naturally receives the sum of all related
contributions.

After all workers finish, the controller merges the private arrays in a stable
order and then assigns disjoint ball ranges back to the workers for the final
apply phase. In that phase each ball is written by only one worker, so the
implementation avoids fine-grained locks while still parallelizing the costly
collision math.

This is different from immediate sequential collision resolution. The threaded
solver treats one tick as a simultaneous set of contact contributions computed
from the same tick-start state, then commits the accumulated result.

The phases have different concurrency properties:

- **Input phase**: asynchronous commands from the human player and bot are
  drained by the controller. They are not executed directly by producer
  threads.
- **Integration phase**: each worker computes the next kinematic state for a
  disjoint chunk of balls. This phase is embarrassingly parallel because each
  ball can be integrated independently against the board boundary.
- **Broad-phase phase**: workers populate local spatial-grid buckets or local
  candidate sets. No shared mutable grid is required during worker execution.
- **Merge phase**: the coordinator combines local buckets/candidates,
  deduplicates pairs, and sorts them by stable ball identifiers.
- **Resolution phase**: collision resolution, pocket removal, score-event
  production, and lifecycle transitions are applied in a single deterministic
  order.
- **Publication phase**: the latest immutable snapshot is atomically replaced
  for the GUI. Rendering never holds model locks.

This design intentionally limits parallelism in the phases where physical
events interact. The objective is not to maximize the number of concurrent
writes, but to maximize useful CPU utilization while preserving a verifiable
state transition function for each tick.

### 6.3 Spatial decomposition policy
The board should be decomposed using a uniform grid rather than a fixed number
of quadrants. Quadrants are easy to explain, but a grid scales better when the
number of balls grows to hundreds or thousands.

The spatial partitioning policy should satisfy the following conditions:

- Cell size must be at least the maximum relevant collision diameter, or each
  ball must be registered in all cells intersected by its bounding box.
- Workers must include border cells when producing collision candidates, since
  collisions can occur across region boundaries.
- Candidate pairs must be represented by stable ball ids or stable indexes and
  normalized as `(minId, maxId)`.
- Duplicate pairs must be removed before resolution.
- The final pair list must be ordered deterministically before applying
  collisions.

The existing `SpatialCollisionDetector` already follows the important
principle of registering a ball in every occupied cell and sorting candidate
pairs. A threaded implementation can preserve the same external contract while
parallelizing the construction of local cell maps and local pair sets.

### 6.4 Worker ownership and monitors
The platform-thread version should use a fixed set of long-lived worker
threads instead of creating threads at every tick. Thread creation belongs to
the runner lifecycle, not to the physics step.

Recommended active objects:

- `ThreadedGameRunner`: owns startup, shutdown, pause/resume, and tick timing.
- `GameControllerThread`: owns game rules and serializes model mutations.
- `PhysicsWorker`: executes assigned physics phases and returns partial
  results.
- `HumanInputAdapter`: translates GUI events into commands and enqueues them.
- `BotThread`: computes bot decisions from immutable snapshots.
- `SnapshotStoreSupport`: stores the latest immutable snapshot for non-blocking GUI
  rendering.

The platform-thread implementation provides all building blocks required by
this architecture: `ThreadedGameRunner`, a controller platform thread, a
monitor-based command queue, immutable snapshot publication, an optional
asynchronous bot platform thread, and a worker-based `ThreadedPhysicsEngine`.
The physics workers parallelize the expensive independent phases of each tick,
while the controller preserves deterministic game-rule ownership and snapshot
publication.

### 6.4.1 Current platform-thread implementation
The current implementation is the final platform-thread runtime for the
multithreaded assignment variant. It introduces active components,
monitor-based communication, and intra-step worker parallelism for the physics
simulation.

Runtime components:

- `ThreadedPoool`: playable launcher. It owns the Swing view loop, reads the
  latest immutable `RuntimeGameSnapshot`, updates `ViewModel`, and requests
  repainting. It does not mutate the game model directly.
- `ThreadedGameRunner`: execution strategy. It owns one `GameModel`
  instance and starts the platform threads used by the runtime.
- `poool-threaded-controller`: controller platform thread. It is the only
  thread that drains game commands, invokes the game model, applies game
  rules, and publishes snapshots.
- `poool-physics-worker-*`: long-lived physics worker platform threads owned by
  `ThreadedPhysicsEngine`. They integrate disjoint ball chunks and build local
  spatial-grid buckets for broad-phase collision detection.
- `poool-threaded-bot`: optional bot platform thread. It reads immutable
  snapshots and submits bot-shot commands asynchronously after the configured
  think time.
- Swing EDT: handles keyboard and mouse events. It produces shot/restart
  requests but does not write to `Board` or `GameModel`.

The resulting ownership structure is:

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
 PhysicsWorker[] for integration and broad phase
        |
        v
 SnapshotStoreSupport
        |
        v
 GUI rendering loop / BotThread
```

This means that the game is already concurrent at the architecture level:
input, bot behaviour, simulation, and rendering are separated into active
components. The expensive physics computation is also parallelized internally:
workers process independent ball ranges for integration and spatial-grid
population. The controller then merges candidate collision pairs and resolves
collisions in stable order.

Current synchronization policy:

- human input and bot actions are submitted as commands through
  `CommandQueueMonitorSupport`;
- submitted shot commands return a `CommandReceiptSupport`, useful for tests and for
  callers that need to wait for controller execution;
- shutdown closes the command monitor and rejects pending commands so callers
  cannot remain blocked waiting for a receipt;
- `SnapshotStoreSupport` publishes immutable snapshots and wakes readers waiting for a
  state condition;
- the GUI reads `RuntimeGameSnapshot` data and never accesses mutable `Ball`
  entities;
- human aiming is enabled only when the current snapshot reports that the human
  cue ball can shoot;
- the bot preview vector is computed by the owned game model and exported in
  the immutable snapshot, then rendered by `ThreadedPoool` when the bot can
  shoot;
- `ThreadedPhysicsEngine` owns long-lived platform workers and coordinates each
  worker phase through `WorkerCompletionMonitor`;
- worker threads never apply game rules, update scores, or publish snapshots;
  those operations remain serialized by the controller/game model.

The current implementation therefore satisfies the intended multithreaded
design: it provides asynchronous player input, asynchronous bot activity,
stable single-writer model ownership, non-blocking snapshot-based rendering,
and worker-based CPU exploitation for large physics configurations.

Remaining design trade-offs:

- collision resolution uses an accumulated-impulse solver, so the threaded
  trajectory can differ from the sequential immediate-resolution trajectory;
- merging local spatial grids is serial and can become visible when almost all
  balls occupy the same region;
- the implementation is expected to scale best on large or massive boards with
  spatially distributed balls.

Recommended custom monitors:

- `CommandQueueMonitorSupport`: bounded or unbounded producer-consumer monitor for
  input, bot, reset, pause, and resume commands.
- `WorkerBarrierMonitor`: reusable barrier for phase completion within one
  physics tick.
- `SnapshotMonitor` or atomic snapshot holder: publishes the latest snapshot
  without exposing mutable board state.
- `LifecycleMonitor`: coordinates start, pause, resume, and shutdown requests.

All monitor methods should keep critical sections short. The implementation
must not call Swing rendering code, bot decision logic, or expensive physics
computation while holding a monitor lock. Every `wait` must be guarded by a
`while` predicate, and shutdown must wake all blocked threads to avoid
termination deadlocks.

### 6.5 Correctness invariants
The following invariants should be treated as design constraints and as test
targets:

- At most one thread applies mutations to the authoritative game and board
  state during a tick.
- Input and bot threads only publish commands; they never mutate the board.
- Physics workers do not access Swing components.
- The GUI reads immutable snapshots, not mutable `Ball` instances.
- Candidate collision pairs are deduplicated and resolved in deterministic
  order.
- Region-boundary collisions are included by construction.
- A reset or shutdown request cannot leave workers permanently blocked at a
  barrier.
- Game-rule updates occur after physics events have been collected for the
  tick.

These invariants directly address the main risks of the thread-based version:
data races, lost updates, deadlocks, GUI starvation, and non-deterministic game
outcomes.

### 6.6 Performance expectations
The thread-based design is expected to improve performance primarily for large
or massive board configurations. On small boards, the overhead of barriers,
snapshot construction, pair merging, and context switching may dominate the
available parallel work. Therefore performance should be evaluated with
multiple configurations:

- minimal board: validates overhead and responsiveness;
- large board: validates useful parallel speedup;
- massive board: validates CPU-core exploitation and stability under load.

The benchmark should compare the sequential baseline with the platform-thread
runner using the same deterministic initial configuration and the same number
of simulated ticks. Useful metrics include average step time, worst-frame
latency, rendered FPS, worker utilization, and speedup relative to the
sequential baseline. The detailed benchmark requirements and metric formulas
are defined in [`benchmarking.md`](benchmarking.md).
The expected academic conclusion is not that every
configuration is faster, but that the threaded architecture scales on
computationally intensive scenarios while preserving responsiveness and
correctness.

## 7. Considered alternatives

### A) Single-thread main loop
Pros:
- simple to implement and debug
- more deterministic behavior

Cons:
- limited scalability
- risk of FPS drop under high load

### B) Aggressive physics parallelization
Pros:
- maximum performance on large/massive configurations

Cons:
- higher risk of races/concurrency bugs
- harder verification

## 8. Architectural decision
Choice: **balanced primary architecture** (separate controller + physics, GUI from snapshots, asynchronous input/bot).

Rationale:
- satisfies all assignment requirements
- keeps strong design clarity
- provides the best tradeoff among performance, robustness, and verifiability

## 9. Concurrency properties and critical scenarios to verify

Properties:
- safety: no data races, no deadlocks, no lost updates
- liveness: guaranteed turn progression, responsive UI, no control-thread starvation

Critical scenarios:
1. user input while physics simulation is running
2. turn switch while bot computation is in progress
3. multiple simultaneous collisions
4. pause/resume during active simulation
5. match reset with pending queued events
6. high physics load without GUI freeze

## 10. Traceability to acceptance requirements
- Concurrent architecture clearly defined: sections 3, 5
- Responsibilities identified: section 3.1
- Synchronization and coordination strategies: section 4
- Shared-state ownership rules: section 4.1
- Physics parallelization strategy: section 6
- Interactions between GUI/simulation/input/bot: section 3.2
- Critical concurrent scenarios to verify: section 9
