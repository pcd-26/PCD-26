# JPF Verification Area

This directory hosts the Java PathFinder verification artifacts for
Assignment 1.

The purpose of this area is to keep the verification models separate from the
Maven build and from the Java 17 production sources. The harnesses are meant
to stay small, Java 11-friendly, and focused on synchronization behavior.

## Planned Contents

- `src/pcd/poool/verification/jpf/ThreadedMiniHarness.java`
- `src/pcd/poool/verification/jpf/TaskBasedMiniHarness.java`
- `threaded-minimal.jpf`
- `taskbased-minimal.jpf`

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

The next task is to implement the two minimal harnesses and connect the JPF
configuration files to their main classes.
