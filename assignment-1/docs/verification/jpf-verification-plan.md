# JPF Verification Plan

This note defines the first verification task for Assignment 1: what we want
to prove with Java PathFinder, what we intentionally leave out, and how the
verification model should stay small enough to explore.

## Goal

Use JPF to verify the concurrent coordination protocol of the minimal
thread-based and task-based runtimes.

The objective is not to re-check the whole game, but to validate the
concurrency properties that are most relevant to the assignment:

- only one actor owns the authoritative board state during a tick;
- commands are drained before the next tick commit;
- worker/task phases complete before snapshot publication;
- the runtime does not deadlock in the minimal protocol;
- the controller can terminate cleanly after a bounded run.

## Systems To Model

We will verify two reduced models:

1. Thread-based minimal model
   - one controller thread;
   - one or two worker threads;
   - a command queue;
   - a board ownership flag;
   - a snapshot publication step.

2. Task-based minimal model
   - one controller thread;
   - a small task submission/join abstraction;
   - a completion barrier;
   - a snapshot publication step.

These models should mirror the synchronization structure of the production
code, but they do not need to mirror the full physics implementation.

## What We Will Verify

The JPF harness should assert the following properties:

- single-writer ownership is preserved for the whole tick;
- no snapshot is published before the tick is fully committed;
- all submitted work for a tick eventually completes in the bounded model;
- no command remains stranded in the queue at commit time;
- no deadlock occurs in the small explored state space;
- the model reaches a stable end state when the bounded scenario finishes.

## What We Will Not Verify

The JPF model should intentionally exclude:

- full collision physics;
- friction, kinematics, and numeric accuracy;
- Swing rendering and event dispatch;
- the bot strategy;
- real `ExecutorService` behavior if it causes state-space explosion;
- large boards or thousands of balls.

## Modeling Rules

To keep the state space manageable:

- use a fixed small number of threads or tasks;
- keep the run bounded to a small number of ticks;
- represent board state with booleans, counters, or small enums when possible;
- avoid I/O, timers, and GUI calls;
- prefer simple monitor-style abstractions over the full production runtime;
- keep the harness deterministic except for the schedule explored by JPF.

## Expected Deliverables

At the end of this task we should have:

- one small JPF harness for the thread-based protocol;
- one small JPF harness for the task-based protocol;
- one `.jpf` configuration per harness;
- a short README or doc section explaining how to run them in Docker;
- a short report-ready summary of what the verification actually proves.

## Success Criterion

The task is complete when JPF can explore the minimal models and either:

- confirm the assertions for the bounded scenario, or
- produce a reproducible counterexample trace that points to a real protocol
  issue.

In both cases, the result must be small enough to explain in the report.
