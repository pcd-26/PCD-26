# Poool Internal Project Notebook

This document is the working base for the team. It collects the facts that are
useful while we develop Assignment 1, combining:

- what the professor asked for;
- what the code currently implements;
- where the supporting documentation lives;
- what we can reuse directly in the final report.

It is not a public README. It is the internal map we should consult before
changing code or writing the report.

## 1. How to use this notebook

When starting a task:

1. read the relevant section below;
2. jump to the linked source or doc file;
3. check the matching tests;
4. update the notebook if the architecture or the report story changes.

This file should stay aligned with the codebase. If a future change introduces a
new runtime, benchmark, or verification artifact, this is the place where we
record it first.

## 2. Assignment brief, in our own words

The assignment asks for:

- a concurrent version of Poool in two variants;
- one variant using platform threads only;
- one variant using the Executor Framework where useful;
- a design that keeps modularity, encapsulation, performance, and reactivity;
- monitors or similar high-level coordination tools whenever possible;
- a brief report with analysis, architecture, Petri nets, benchmarks, and JPF
  verification.

The game rules described in the brief are:

- the board has small balls plus two cue balls, one human and one bot;
- players hit their cue ball by applying an impulse;
- small balls pocketed by a player increase that player’s score;
- if a cue ball goes into a hole, the other player wins immediately;
- the bot does not need to be smart;
- players act asynchronously.

The deliverable must include the source code and a `doc` directory with the
final PDF report.

## 3. What the professor asked for, and where it is covered

| Requirement from the brief | Where it is covered | Status |
| --- | --- | --- |
| concurrent platform-thread version | `pcd.poool.ThreadedPoool`, `pcd.poool.threaded.*`, `pcd.poool.model.physics.threaded.*` | implemented |
| concurrent task-based version | `pcd.poool.TaskBasedPoool`, `pcd.poool.taskbased.*`, `pcd.poool.model.physics.taskbased.*` | implemented |
| use high-level coordination where possible | `CommandQueueMonitor`, `SnapshotStore`, `WorkerCompletionMonitor`, `BoundedBufferImpl` | implemented |
| preserve modularity and encapsulation | shared model packages under `pcd.poool.model.*` | implemented |
| performance comparison against sequential baseline | benchmark package and CSV/chart export flow | implemented |
| report with problem analysis and design | `docs/concurrent-architecture.md`, `docs/runtime-architecture.md` | documented |
| report with Petri nets | `docs/verification/petri-nets.md` and `.tex` sources | documented |
| report with JPF verification | `docs/verification/jpf-verification-plan.md`, `docs/verification/jpf-models.md`, `docs/verification/jpf-docker-workflow.md` | documented |

The codebase already satisfies the main structural requests. The remaining work
is mostly about keeping the documentation, benchmarks, and final report
consistent.

## 4. Source of truth map

### 4.1 Code

The main delivery code lives under:

- [`assignment-1/src/main/java/pcd/poool`](../src/main/java/pcd/poool)

The test suite lives under:

- [`assignment-1/src/test/java`](../src/test/java)

### 4.2 Documentation

The most important internal docs are:

- [`docs/runtime-architecture.md`](runtime-architecture.md)
- [`docs/physics-engines-and-concurrency.md`](physics-engines-and-concurrency.md)
- [`docs/concurrent-architecture.md`](concurrent-architecture.md)
- [`docs/threaded-implementation.md`](threaded-implementation.md)
- [`docs/benchmarking.md`](benchmarking.md)
- [`docs/poool-code-scope.md`](poool-code-scope.md)
- [`docs/performance/physics-architecture-analysis.md`](performance/physics-architecture-analysis.md)
- [`docs/verification/jpf-verification-plan.md`](verification/jpf-verification-plan.md)
- [`docs/verification/jpf-models.md`](verification/jpf-models.md)
- [`docs/verification/jpf-docker-workflow.md`](verification/jpf-docker-workflow.md)
- [`docs/verification/petri-nets.md`](verification/petri-nets.md)
- [`docs/verification/petri-net-verification-summary.md`](verification/petri-net-verification-summary.md)

### 4.3 Public-facing README

The public README in `assignment-1/README.md` still contains both user-facing and
internal material. When we work on the `Remove non-prof docs from README` task,
that file should be trimmed and the internal content should remain in this docs
tree.

## 5. Codebase map, by responsibility

### 5.1 Launchers

- `pcd.poool.SequentialPoool`
  - sequential playable launcher and baseline runtime.
- `pcd.poool.ThreadedPoool`
  - platform-thread launcher.
- `pcd.poool.TaskBasedPoool`
  - executor-based launcher.

### 5.2 Shared domain model

- `pcd.poool.model.common.math`
  - immutable points and vectors.
- `pcd.poool.model.physics.common`
  - board, balls, holes, boundaries, spatial collision helpers, stepper seam.
- `pcd.poool.model.physics.config`
  - deterministic board configurations for play, stress, and benchmarking.
- `pcd.poool.model.game`
  - score, status, winner, game-over reason, and game snapshot.

### 5.3 Concurrency support

- `pcd.poool.model.concurrent`
  - reusable bounded-buffer monitor.
- `pcd.poool.controller`
  - sketch-derived active controller abstraction.
- `pcd.poool.threaded`
  - threaded runtime coordinator, command queue, snapshot publication, bot.
- `pcd.poool.taskbased`
  - task-based runtime coordinator and executor-oriented equivalents.
- `pcd.poool.runtime`
  - shared runtime support interfaces and helpers.

### 5.4 Physics engines

- `pcd.poool.model.physics.sequential.PhysicsEngine`
  - sequential reference stepper.
- `pcd.poool.model.physics.threaded.ThreadedPhysicsEngine`
  - platform-thread worker-based stepper.
- `pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine`
  - executor-based stepper.

### 5.5 View and rendering

- `pcd.poool.view`
  - rendering helpers and synchronization support.
- `pcd.poool.view.board`
  - Swing-facing frame, view model, and facade.

### 5.6 Benchmarks and verification

- `pcd.poool.benchmark`
  - benchmark configuration, runners, post-processing, CSV export.
- `pcd.poool.verification`
  - JPF test harnesses and verification artifacts.

## 6. Current implementation story

### 6.1 Sequential baseline

The sequential launcher remains the semantic reference.

What it does:

- owns a single `GameModel`;
- advances physics on one thread;
- uses the same shared physics and game rules as the concurrent runtimes;
- publishes copied state to the view.

Why it matters:

- it gives us the baseline for both correctness and benchmark comparison;
- it provides the simplest explanation of the game rules and state changes;
- it is the fallback when we want to debug a behavior without concurrency noise.

### 6.2 Platform-thread runtime

The platform-thread runtime is centered around:

- `ThreadedGameRunner`;
- `ThreadedPhysicsEngine`;
- `ThreadedBotAgent`;
- `CommandQueueMonitor`;
- `SnapshotStore`.

The key design idea is single-writer ownership of the authoritative game
state. Input and bot actions are queued as commands, the controller thread
drains them, physics is stepped through the threaded physics engine, and the
view receives immutable snapshots.

### 6.3 Task-based runtime

The task-based runtime mirrors the threaded one, but uses an executor-backed
physics engine.

The important point for the report is that:

- the execution policy changes;
- the shared game semantics do not;
- the same high-level state ownership rules still apply.

### 6.4 View model and snapshots

The GUI never reads the mutable model directly.

Instead, it consumes:

- `GameSnapshot` for logical game state;
- `ThreadedGameSnapshot` or `TaskBasedGameSnapshot` for runtime-specific data;
- `ViewModel` for rendering copies;
- `RenderSynch` for repaint coordination.

This is the main reason the GUI stays decoupled from the simulation threads.

## 7. Concurrency rules we should preserve

These are the architectural rules we should treat as stable:

- the mutable board has a single writer at a time;
- the game model is the authority for score, readiness, winner, and status;
- worker threads compute parallel phases but do not own the game rules;
- the GUI reads snapshots, not live mutable balls;
- command submission is asynchronous;
- shutdown must wake blocked waiters and not leave dangling threads;
- deterministic ordering matters when merging collision work or scoring
  results.

If any future change violates one of these rules, the change should explain why
and should come with a focused test.

## 8. Requirements coverage notes

### 8.1 What is clearly respected

- concurrent version in two variants: yes;
- sequential baseline for comparison: yes;
- monitors and similar high-level coordination: yes;
- immutable snapshots for GUI/rendering: yes;
- benchmark workflow for performance comparison: yes;
- JPF verification scope and execution plan: yes;
- Petri nets for behavior description: yes.

### 8.2 What should be checked before final report submission

- that the README only contains public-facing material;
- that the benchmark results used in the report are the intended final ones;
- that the report narrative matches the current implementation, not an older
  design note;
- that any final claim about speedup is backed by the benchmark CSVs and charts.

## 9. Benchmark material to reuse in the report

Use the benchmark docs and outputs as the report’s data spine:

- [`docs/benchmarking.md`](benchmarking.md)
- `benchmarks/results/`
- `benchmarks/charts/`

The report should normally compare:

- sequential baseline;
- platform-thread version;
- task-based version;
- worker-count scaling where relevant;
- the fixed scenarios documented in the benchmark notes.

For the oral discussion, the most important benchmark story is not "best run",
but "stable median behavior on the same machine/JVM pair".

## 10. Verification material to reuse in the report

The verification story is already split into three layers:

- `docs/verification/jpf-verification-plan.md`
  - what we want to verify and what we intentionally do not model.
- `docs/verification/jpf-models.md`
  - the minimal JPF model semantics.
- `docs/verification/jpf-docker-workflow.md`
  - how to run the checks.

The Petri-net story is split into:

- `docs/verification/petri-nets.md`
  - high-level behavior model and mapping to code.
- `docs/verification/petri-net-verification-summary.md`
  - report-ready summary.

That is enough to write the verification section of the final report without
inventing a separate narrative from scratch.

## 11. Report outline we can reuse

When we start the final report, the draft can follow this structure:

1. problem statement and concurrency challenges;
2. design goals and architecture;
3. sequential baseline and shared model;
4. platform-thread runtime;
5. task-based runtime;
6. benchmark methodology and results;
7. verification with Petri nets and JPF;
8. conclusions and trade-offs.

This outline already matches the current docs tree, so the report should be a
composition of the existing documentation plus the final benchmark outputs.

## 12. Working checklist for contributors

Before closing a change:

- check whether the change affects docs, architecture, benchmarks, or
  verification;
- update the relevant file under `docs/`;
- add or update focused tests under `src/test/java`;
- run the Maven test suite;
- if the change affects performance claims, refresh the benchmark evidence;
- if the change affects concurrency or behavior, reflect it in the internal
  notebook here.

## 13. Open points to keep in mind

These are not blockers, but they are the things we should watch while polishing
the final delivery:

- the public README still mixes user-facing and internal material;
- the final report should quote the current implementation, not older notes;
- benchmark claims must stay tied to the actual CSVs and charts we decide to
  include;
- any future concurrency change should update the architecture and verification
  docs together.

## 14. Core concepts we can report on

This is the part that should feed the final report most directly. The report
does not need to mirror the code file-by-file. It should explain the concepts
behind the implementation.

### 14.1 Single-writer ownership

The most important concept in the project is that mutable authoritative state
has one owner at a time.

What to say in the report:

- the `Board` is the physical state owner;
- the `GameModel` is the game-rule owner;
- the controller thread serializes the logical match progression;
- worker threads compute parallel work, but do not become extra writers.

Why it matters:

- it prevents races on the board and on the score;
- it makes the design easier to reason about;
- it gives a simple explanation for why the GUI can safely read snapshots.

### 14.2 Sequential baseline as reference semantics

The sequential version is not just a fallback. It is the semantic baseline.

What to say in the report:

- the sequential runner defines the reference behavior;
- the concurrent versions reuse the same model and game rules;
- benchmarks compare against the sequential baseline;
- any concurrency optimization must preserve the same game meaning.

### 14.3 Monitor-based coordination

The assignment explicitly asked for high-level coordination constructs when
possible, and the code uses that idea in several places.

What to say in the report:

- commands are queued through monitors;
- worker completion is coordinated through a barrier-like monitor;
- snapshot publication is isolated from the mutable state;
- the design prefers explicit waiting/notification points over low-level
  thread sharing.

Useful terms:

- producer/consumer;
- bounded or serialized command queue;
- barrier;
- wait/notify protocol;
- controlled shutdown.

### 14.4 Immutable snapshots

The GUI and benchmark consumers do not read live mutable balls directly.

What to say in the report:

- the simulation state is copied into immutable or copy-based snapshots;
- rendering is separated from simulation mutation;
- snapshots protect the view from concurrent writes;
- this improves safety and makes testing and benchmarking easier.

This is a very good concept to emphasize in the report because it connects
correctness, thread safety, and clean architecture.

### 14.5 Physics tick pipeline

The simulation is best explained as a pipeline of phases rather than as "one
big physics method".

What to say in the report:

- a tick integrates movement;
- holes are applied;
- collision candidates are built;
- collisions are resolved;
- the resulting state is committed;
- only after that a snapshot is published.

Why it matters:

- it makes the concurrent design easier to explain;
- it gives a natural place to describe where parallel work happens and where
  serialization remains necessary;
- it is the right abstraction for both the physics docs and the report.

### 14.6 Spatial decomposition and worker parallelism

The physics engines do not parallelize the whole game. They parallelize the
computationally heavy parts of the tick.

What to say in the report:

- the board is split into disjoint work ranges or cells;
- workers compute in parallel on private or disjoint data;
- the coordinator merges the results deterministically;
- this is where the speedup comes from.

This is the right place to mention:

- data partitioning;
- candidate-pair generation;
- deterministic ordering;
- merge cost;
- Amdahl's law and why speedup is workload-dependent.

### 14.7 Determinism versus parallelism

The project tries to preserve deterministic results even while using multiple
threads.

What to say in the report:

- the sequential engine is deterministic by construction;
- the concurrent engines keep deterministic merge and commit rules;
- the goal is not maximum nondeterminism for speed, but controlled
  concurrency with stable semantics;
- if there are differences between engines, they should be explained in terms
  of execution strategy, not game rules.

### 14.8 Asynchronous input and bot behavior

Input and bot actions are part of the concurrency story, not just UI details.

What to say in the report:

- the human and bot act asynchronously;
- commands are submitted independently of the physics loop;
- the controller decides when commands are accepted and applied;
- this keeps interaction responsive without letting input threads mutate the
  board directly.

### 14.9 Verification and abstraction

The JPF and Petri-net work should be explained as abstract verification, not
as a proof of the entire game.

What to say in the report:

- the verification model is intentionally smaller than the full simulation;
- it focuses on ownership, ordering, barriers, and shutdown safety;
- it does not try to model floating-point physics in detail;
- the goal is to verify the protocol, not the exact billiards math.

### 14.10 Performance as a trade-off

The report should present performance as a trade-off, not as a pure victory
story.

What to say in the report:

- concurrency adds coordination cost;
- small workloads may not benefit much;
- larger workloads are where parallelism matters;
- the benchmark evidence should show median behavior, not a cherry-picked
  best-case run.

## 15. Report-ready concept list

If we want a short list of concepts to reuse almost verbatim in the report, it
is this one:

- single-writer ownership;
- sequential baseline as semantic reference;
- monitors and controlled message passing;
- immutable snapshots for rendering and tests;
- physics tick as a multi-phase pipeline;
- spatial decomposition for parallelism;
- deterministic merge and commit;
- asynchronous input and bot commands;
- abstract verification with Petri nets and JPF;
- benchmark comparison by median execution time and speedup.

That is the real conceptual spine of the assignment. The rest of the report
should explain how the code implements these ideas.
