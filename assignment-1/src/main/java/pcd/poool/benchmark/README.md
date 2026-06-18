# Benchmark package

This directory contains the benchmark entry points used to measure the cost of
the main simulation variants.

## Purpose

The benchmark package is not part of the gameplay runtime. Its role is to run
controlled workloads and report timing data useful for the assignment report.

## Files

- `PhysicsBenchmark.java`
  Benchmarks the sequential physics engine on a large physical configuration.
- `SequentialGameBenchmark.java`
  Benchmarks the integrated sequential gameplay loop, including physics and
  game-rule progression.
- `ThreadedPhysicsBenchmark.java`
  Benchmarks the multithreaded physics engine and can optionally choose the
  number of worker threads.
- `ThreadedPhysicsProfilingBenchmark.java`
  Profiles the threaded physics pipeline phase by phase and compares a sparse
  and a clustered layout to highlight possible bottlenecks in broad-phase
  collision handling.

## Relationships

- Uses board configurations from `model.physics.config`.
- Uses `Board`, `GameModel`, `PhysicsEngine`, or `ThreadedPhysicsEngine`
  depending on the workload being measured.
- Produces execution-time data, but does not affect the main game logic.
