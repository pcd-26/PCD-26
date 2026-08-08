# Physics engine and concurrency

Poool has one sequential engine and two separate concurrent engines. This is
the central simplification of the final architecture.

## Components

- `SequentialPhysicsEngine` is the single-threaded correctness baseline.
- `ThreadedPhysicsEngine` contains the full platform-thread step algorithm.
- `TaskBasedPhysicsEngine` contains the full executor-based step algorithm.
- `PlatformThreadRangeScheduler` owns reusable platform-thread workers and a
  custom completion monitor.
- `ExecutorRangeScheduler` submits ranges to a fixed `ExecutorService` and
  waits on their futures.

The platform-thread and task-based versions therefore still follow the same
physics rules, even though their implementations are separate.

## One parallel step

`ThreadedPhysicsEngine.step(board, elapsedMillis)` and
`TaskBasedPhysicsEngine.step(board, elapsedMillis)` keep the board under one
writer and split a long elapsed duration into fixed sub-steps. Each sub-step:

1. collects active balls;
2. integrates disjoint ball ranges in parallel;
3. applies hole interactions on the coordinator;
4. builds one private spatial grid per worker/task;
5. merges and orders grid cells deterministically;
6. computes collision contributions in parallel into private accumulators;
7. sorts contact pairs, merges deltas, and commits the result;
8. returns only after the scheduler's phase barrier completes.

Workers never add scores, remove balls, or mutate the game lifecycle. They
only process disjoint balls or write private collision accumulators.

## Why the merge is serial

Collisions can touch the same ball from different cells. Direct concurrent
writes would make outcomes depend on scheduling. Workers therefore calculate
position and velocity deltas privately. The coordinator merges those deltas
in a stable order and records contacts on `Board`.

This serial commit is an intentional correctness boundary. It preserves:

- deterministic seeded runs;
- stable scoring attribution;
- a single mutable board owner;
- equivalent semantics for both concurrent implementations.

## Difference between the required versions

### Platform threads

`PlatformThreadRangeScheduler` creates its `PhysicsWorker` threads once. For a
phase it partitions the index range, assigns one chunk to each worker, and
waits on `WorkerCompletionMonitor`. No executor is used.

### Executor Framework

`ExecutorRangeScheduler` owns a fixed executor pool. Large phases become
executor tasks and `Future.get()` is the barrier. Small phases run inline to
avoid task-allocation overhead.

Both implementations satisfy the assignment independently while sharing the
domain and numerical algorithm.

## Synchronization rule

The rule is: parallel computation, serialized commit.

The controller owns `GameModel`; the physics step owns `Board`; schedulers own
only their worker lifecycle. Swing and the bot communicate through immutable
snapshots and `CommandMailbox`, never through mutable balls.
