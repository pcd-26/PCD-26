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
- `CompletePhysicsBenchmark.java`
  Runs the recommended automatic comparison across the sequential,
  platform-threaded, and task-based physics engines. It uses the same
  deterministic scenarios, warmup, repeat count, and checksum validation for
  every implementation.

## Recommended comparison

Run the complete benchmark from the repository root after compiling:

```bash
mvn -f assignment-1/pom.xml package
java -cp assignment-1/target/classes pcd.poool.benchmark.CompletePhysicsBenchmark 600 50 5
```

Arguments are:

```text
measured_steps warmup_steps repeats
```

The output is line-oriented. `kind=result` lines contain the raw comparable
measurements:

- `scenario`: small, medium, or high-load board
- `engine`: sequential, threaded, or task
- `workers`: worker count used by threaded/task engines
- `avg_step_ms`, `min_step_ms`, `max_step_ms`, `stddev_step_ms`
- `throughput_steps_per_sec`
- `speedup_vs_sequential`
- `checksum_matches_sequential`
- `diagnosis`

`kind=recommendation` lines report the fastest engine for each scenario. A
diagnosis of `parallel_overhead_dominates` means the concurrent strategy is
slower than the sequential baseline for that workload. A diagnosis of
`different_trajectory_check_rules` means the implementation is deterministic
across repeats but its accumulated collision solver produced a different final
floating-point trajectory from the sequential immediate-resolution baseline;
in that case gameplay rule tests should be used together with timing data.

## Relationships

- Uses board configurations from `model.physics.config`.
- Uses `Board`, `GameModel`, `physics.sequential.PhysicsEngine`, or
  `physics.threaded.ThreadedPhysicsEngine` depending on the workload being
  measured.
- Produces execution-time data, but does not affect the main game logic.
