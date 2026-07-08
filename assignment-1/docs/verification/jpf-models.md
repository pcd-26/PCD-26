# JPF Minimal Models

This note explains the two minimal harnesses used for model checking with JPF
and the exact properties they are meant to cover.

## Shared Design

Both models use the same tiny shared state:

- a command counter;
- a board ownership flag;
- a work-ready flag;
- a worker completion flag;
- a snapshot-published flag;
- a finished flag.

This is enough to capture the concurrency protocol without pulling in the full
physics engine or the GUI.

## Thread-Based Model

Target:

- `pcd.poool.verification.jpf.ThreadedMiniHarness`

Intended scenario:

- one producer submits a command;
- the controller waits for the command, owns the board, drains the queue, and
  starts the work phase;
- one worker waits for the work signal, completes the work, and notifies the
  controller;
- the controller publishes the snapshot only after the worker completion.

Properties checked:

- the board cannot be owned by two actors at once;
- the worker cannot complete work before ownership is established;
- commands are drained before publish;
- the final state is clean and finished.

## Task-Based Model

Target:

- `pcd.poool.verification.jpf.TaskBasedMiniHarness`

Intended scenario:

- one producer submits a command;
- the controller waits for the command, owns the board, drains the queue, and
  creates a short-lived task;
- the task waits for work availability and completes the work;
- the controller joins the task and then publishes the snapshot.

Properties checked:

- command draining still happens before publication;
- the task model keeps the same single-writer rule;
- the completion path is bounded and terminates cleanly;
- the final state is clean and finished.

## Why These Models Are Useful

The models are intentionally small, but they still exercise the most important
concurrent invariants from the production runners:

- the controller is the only component allowed to mutate the authoritative
  state;
- workers/tasks only perform bounded auxiliary work;
- publication happens after commit, not before;
- shutdown can be reasoned about as a terminal state.

## What They Do Not Prove

These models do not prove:

- numerical correctness of the physics;
- collision determinism under large workloads;
- GUI responsiveness;
- throughput or performance scaling;
- bot intelligence.

They are only a model-checking proof of the control protocol.
