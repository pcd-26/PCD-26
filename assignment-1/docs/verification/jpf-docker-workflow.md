# JPF Docker Workflow

This document describes the intended execution flow for the minimal JPF
verification models in Assignment 1.

The workflow is intentionally split from the Maven build:

- the production project stays on Java 17 and Maven;
- the JPF harnesses stay small and separate;
- Docker provides the JPF runtime environment.

## 1. Prepare JPF

Use the official `jpf-core` repository and its Docker support.

The JPF project README states that the container can be built and started with
the provided `Dockerfile` and `docker-compose.yml`.

Inside that container, JPF can be built from source with the usual Gradle
build.

## 2. Compile The Minimal Harnesses

The harnesses live under:

- `assignment-1/verification/jpf/src/pcd/poool/verification/jpf`

They should be compiled separately from Maven, for example into a local build
directory dedicated to verification classes.

The important point is that the resulting classpath must include only the
minimal harness classes and the JPF runtime, not the full application build.

## 3. Run The Thread-Based Model

The thread-based config is:

- `assignment-1/verification/jpf/threaded-minimal.jpf`

The run should target:

- `pcd.poool.verification.jpf.ThreadedMiniHarness`

The expected outcome is that JPF explores the small state space and either
confirms the assertions or produces a short counterexample trace.

## 4. Run The Task-Based Model

The task-based config is:

- `assignment-1/verification/jpf/taskbased-minimal.jpf`

The run should target:

- `pcd.poool.verification.jpf.TaskBasedMiniHarness`

As with the threaded model, the goal is to verify the protocol around command
draining, work completion, and snapshot publication.

## 5. What To Record

For the report, record:

- the command used to start the JPF container;
- the command used to compile the harness classes;
- the exact JPF invocation for each model;
- whether JPF found a counterexample or completed cleanly;
- the main limitation of the model, if any.

## 6. Practical Verification Notes

- Keep the harness small and deterministic.
- Keep the number of threads or tasks minimal.
- Avoid GUI and the full physics engine.
- Prefer bounded runs so the state space stays manageable.
- If the model explodes, reduce the number of actors before changing the
  properties.
