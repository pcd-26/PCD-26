# JPF Minimal Models

This note describes the reduced concurrent models checked with Java
PathFinder. They retain the synchronization boundaries of the game runtime
without importing physics, Swing, or the production executor implementation.

## Shared design

Both models contain two independently scheduled command producers and track
each command from submission to exact-once execution. The controller owns the
board for a tick, drains the commands visible at that point, dispatches one
generation of two work units, waits at a two-worker completion barrier, and
only then publishes a snapshot.

The state stores a small mailbox counter, command submission/execution flags,
a board-ownership flag, generation and worker-completion flags, a publication
counter, and a shutdown flag. A command that arrives while a physics phase is
running remains pending for the following tick; this mirrors the mailbox
boundary in the production runtime.

## Thread-based model

Target:

- `pcd.poool.verification.jpf.ThreadedMiniHarness`

The model uses two long-lived workers. Each waits for a new generation, checks
that the controller owns the board, completes its assigned generation once,
and returns to waiting. After all submitted commands have been executed, the
controller signals shutdown and joins both workers.

## Task-based model

Target:

- `pcd.poool.verification.jpf.TaskBasedMiniHarness`

For each tick the controller creates two short-lived task threads. They
complete the same two-worker barrier, then the controller publishes the
snapshot and joins both task threads. This models task submission plus a
completion barrier without depending on the full `ExecutorService` state
space.

## Direct threaded-physics verification

Target:

- `pcd.poool.model.physics.threaded.PhysicsWorkersJpfHarness`

This harness directly instantiates the production `PhysicsWorker` and
`WorkerCompletionMonitor` classes. It creates two long-lived workers and
assigns each of them one chunk in two consecutive phases. JPF explores the
interleavings among assignment, worker wake-up, execution, barrier completion,
commit, reuse, and shutdown.

The harness asserts that a worker executes exactly one chunk per phase and
that the coordinator can commit a phase only after both chunks have completed.
The chunk body records a bounded observable effect rather than running the full
collision algorithm, keeping the state space finite. It therefore verifies the
actual worker/barrier implementation, but not numerical physics.

## Task-based physics validation model

Target:

- `pcd.poool.model.physics.taskbased.TaskBasedPhysicsBatchHarness`

This verification-only harness recreates the scheduling boundary of
`TaskBasedPhysicsEngine`: a fixed `ExecutorService` receives every chunk of a
phase before the coordinator waits for the batch barrier. It submits two chunks
for each of two phases.

JPF checks that each task executes once in each phase, that commit happens only
after the whole batch has completed, and that the pool is reused and then
closed. The task body is bounded, so this validates task submission and the
barrier protocol rather than the production engine or numerical physics.

## Properties checked

Both models assert that:

- the board cannot be acquired twice for one tick;
- a worker/task cannot complete without controller ownership or for the wrong
  generation;
- a worker/task cannot complete one generation twice;
- publication waits for two distinct work completions and for command draining;
- both submitted commands are executed exactly once;
- completion releases board ownership, leaves no pending command, and reaches
  a stopped state.

JPF also reports whether the finite model contains a deadlock. The current
models are exhaustive finite protocol checks, not proofs of the full game.

## Scope limits

These models do not verify numerical physics, collision determinism under
large workloads, Swing responsiveness, throughput, or the implementation of
`ExecutorService`. Production JUnit tests and benchmarks cover those separate
concerns.
