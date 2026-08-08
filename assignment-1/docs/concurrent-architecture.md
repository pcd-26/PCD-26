# Concurrent architecture and requirement traceability

## Design rule

Poool uses parallel computation with serialized ownership. External components
submit intentions; one controller applies game mutations; physics workers
calculate independent ranges; one deterministic commit updates the board.

## Active components

- Swing EDT: translates keyboard/mouse interaction into commands and paints
  snapshots.
- `BotAgent`: observes snapshots and asynchronously submits bot shots.
- Runtime controller: a platform thread or scheduled Executor task invoking the
  same `GameLoop`.
- Physics scheduler: reusable platform workers or fixed-pool Executor tasks.

## Shared state

| State | Owner | Readers |
| --- | --- | --- |
| game rules | `GameLoop` controller through `GameModel` | immutable snapshots |
| physical board | current physics step | controller snapshot creation |
| pending commands | `CommandMailbox` monitor | controller drain |
| latest snapshot | `GameLoop` monitor | GUI, bot, tests |
| worker phase completion | scheduler barrier | physics coordinator |

## Assignment requirements

| README requirement | Implementation |
| --- | --- |
| platform-thread concurrent version | `ThreadedGameRunner`, `PlatformThreadRangeScheduler`, `PhysicsWorker` |
| task-based Executor version | `TaskBasedGameRunner`, `ExecutorRangeScheduler` |
| modularity and encapsulation | shared `GameLoop`, `GameModel`, `Board`, and separate `ThreadedPhysicsEngine` / `TaskBasedPhysicsEngine` implementations |
| high-level/custom monitor | `CommandMailbox` and `WorkerCompletionMonitor` |
| asynchronous players | Swing and `BotAgent` submit commands independently |
| high ball count | spatial grid and parallel range processing |
| sequential comparison | `SequentialPhysicsEngine` and seeded benchmark adapter |
| core exploitation | worker-count scalability benchmark |
| model checking | minimal JPF protocol harnesses |
| Petri Nets | command/tick and physics-phase models |

## Reused sketches

The immutable math, board, and Swing starting point came from sketch 01. The
active-controller idea came from sketch 02, but generic demo classes are kept
only under `reference/`. Production uses the smaller domain-specific
`CommandMailbox`.

## Safety properties

- no UI or bot mutation of `GameModel`;
- FIFO command execution and explicit rejection on shutdown;
- no concurrent writes to the same ball during range phases;
- phase barriers before merge and snapshot publication;
- deterministic contact ordering and seeded fingerprints;
- idempotent runtime and scheduler shutdown.

## Liveness properties

- submitted commands eventually execute or are rejected;
- controller ticks continue under concurrent producer load;
- worker failure releases the barrier and propagates to the controller;
- task failure becomes visible to callers;
- GUI rendering never waits for mutable physics state.
