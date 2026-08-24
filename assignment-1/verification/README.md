# Verification artifacts

This directory is part of the Assignment 1 deliverable. It contains the Java
PathFinder (JPF) artifacts used to verify bounded concurrent scenarios.

The material is included for inspection: running JPF is not required to read
or assess the assignment.

## Contents

`jpf/` contains:

- the four Java verification harnesses under `src/`;
- the four corresponding `.jpf` configurations;
- `README.md`, which states the scope and limits of each harness.

The delivery archive deliberately excludes the JPF runtime, generated outputs,
and launcher scripts. They are not needed to inspect the verification and would
only be required to reproduce the runs from the repository.

The harnesses cover board ownership, exact-once command processing, snapshot
publication after phase completion, worker/task completion before commit, and
bounded termination. They are separate from the production sources under
`src/` and do not alter the game implementation.

The report summarizes the checked properties and the JPF exploration results.
