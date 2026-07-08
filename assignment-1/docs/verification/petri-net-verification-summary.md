# Petri Net Verification Summary

Petri nets were used to verify the concurrent synchronization protocol of
Assignment 1. The goal was not to model the full billiards physics or the
numerical details of ball motion. Instead, the model captures how one physics
tick is coordinated across threads or tasks, how shared state is protected, and
how the system moves from one phase to the next in a safe order.

## Modeled System Boundaries

The model covers the concurrent part of the runtime only:

- command draining from the controller queue;
- ownership of the board during a tick;
- worker execution for parallel physics phases;
- barrier synchronization between phases;
- snapshot publication after the tick is complete.

The model excludes:

- low-level collision formulas;
- friction and kinematic equations;
- exact ball positions and velocities;
- GUI rendering details;
- bot strategy and input semantics beyond command submission.

## Main Places and Transitions

The main places in the net represent control tokens for the tick pipeline:

- `TickReady`
- `BoardWriteOwned`
- `CommandsPending`
- `IntegrationWorkReady`
- `IntegrationDone`
- `HolePhaseReady`
- `LocalCollisionWorkReady`
- `LocalCollisionDone`
- `CrossCellCollisionWorkReady`
- `CrossCellCollisionDone`
- `SnapshotPublishReady`
- `SnapshotPublished`

The main transitions represent the ordered phases of the runtime:

- `StartTick`
- `DrainCommands`
- `DispatchIntegration`
- `JoinIntegration`
- `ApplyHoleInteractions`
- `DispatchLocalCollisionWork`
- `JoinLocalCollisionWork`
- `DispatchCrossCellCollisionWork`
- `JoinCrossCellCollisionWork`
- `PublishSnapshot`
- `FinishTick`

These elements describe the synchronization protocol used by both the
platform-thread and executor-based physics implementations.

## Informal Safety Properties

The Petri net supports the following safety arguments:

1. Phase ordering is preserved.
   The collision phases are not enabled until integration and hole processing
   have finished, so the system does not resolve contacts on a half-updated
   board.
2. Shared board mutation remains single-writer.
   Workers compute partial results, but the controller owns the authoritative
   board state during the tick.
3. Cross-cell collision work is not performed concurrently on shared mutable
   state.
   Parallel workers operate on private ranges or private accumulators, and the
   merge step is serialized.
4. Snapshot publication happens only after the tick reaches a consistent
   commit point.

## Informal Liveness Properties

The model also supports informal liveness checks:

- workers eventually complete their assigned phase chunks;
- the barrier eventually opens when all chunks finish;
- one tick eventually completes under normal execution;
- the next tick can start again after the snapshot is published;
- the protocol does not deadlock in normal execution, assuming workers are
  scheduled and no unrecoverable failure occurs.

## Limitations

This is an abstract verification model, so it has limits:

- it does not prove the numerical correctness of collision response;
- it does not model every internal data structure of the implementation;
- it assumes fair scheduling and normal termination conditions;
- it abstracts away performance details such as task granularity and worker
  load balance;
- it treats snapshots and worker completion as synchronization outcomes rather
  than full object-level state.

## Why This Helps the Report

The model directly supports the report requirement to describe the behavior of
the concurrent system. It provides a concise, structured explanation of how the
physics tick is coordinated, which phases are synchronized, and which safety
and liveness properties can be argued from the design.
