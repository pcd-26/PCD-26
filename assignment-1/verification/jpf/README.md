# JPF Verification Area

This directory hosts the Java PathFinder verification artifacts for
Assignment 1.

The purpose of this area is to keep the verification models separate from the
Maven build and from the Java 17 production sources. The harnesses are meant
to stay small, Java 11-friendly, and focused on synchronization behavior.

## Contents

- `src/pcd/poool/verification/jpf/ThreadedMiniHarness.java`
- `src/pcd/poool/verification/jpf/TaskBasedMiniHarness.java`
- `threaded-minimal.jpf`
- `taskbased-minimal.jpf`
- `target/jpf-classes` during test execution

## Scope

The harnesses model only the concurrent protocol:

- command submission and draining;
- exclusive board ownership during a tick;
- work dispatch and completion;
- snapshot publication after commit;
- bounded termination of the scenario.

They do not model:

- full physics;
- GUI rendering;
- bot strategy;
- large workloads;
- production `ExecutorService` behavior.

## Next Step

The next step is to run the JPF integration test with the JPF runtime classpath
provided through `JPF_CP` or `-Djpf.cp`.

## Execution Notes

The recommended execution flow is documented in
[`docs/verification/jpf-docker-workflow.md`](../../docs/verification/jpf-docker-workflow.md).
In short, the JUnit test compiles the harnesses into
`assignment-1/target/jpf-classes` and then runs both `.jpf` files through the
JPF launcher exposed by `JPF_CP` or `-Djpf.cp`.

The model-level explanation is documented in
[`docs/verification/jpf-models.md`](../../docs/verification/jpf-models.md).
