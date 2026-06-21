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
- `BenchmarkConfig.java`
  Shared benchmark configuration model used by all benchmark runners.
- `BenchmarkRunner.java`
  Shared timing and aggregation infrastructure. It produces raw
  `BenchmarkRunResult` values for each warmup and measured run, then
  summarizes the measured runs into a `BenchmarkSummary`.
- `BenchmarkRunResult.java`
  Immutable raw measurement for a single run, including elapsed time,
  throughput, checksum, and status.
- `BenchmarkSummary.java`
  Aggregate statistics for a benchmark session, kept separate from the raw
  per-run measurements.
- `BenchmarkCsvWriter.java`
  Appends raw runs and aggregate summaries to stable CSV files in the
  configured output directory.
- `BenchmarkSuite.java`
  Executes the full benchmark matrix, prints progress, and stores all results
  in a timestamped directory under `benchmarks/results/`.
- `HeadlessSimulationRunner.java`
  Runs a seeded simulation without GUI rendering and reports elapsed time,
  completed steps, and a final board-state hash for the selected execution
  strategy.
- `ThreadedPhysicsBenchmark.java`
  Benchmarks the multithreaded physics engine and can optionally choose the
  number of worker threads.
- `ThreadedPhysicsProfilingBenchmark.java`
  Profiles the threaded physics pipeline phase by phase and compares a sparse
  and a clustered layout to highlight possible bottlenecks in broad-phase
  collision handling.
- `TaskBasedPhysicsProfilingBenchmark.java`
  Profiles the task-based physics pipeline phase by phase using the engine's
  internal profiling snapshot. It reports the time spent in integration, hole
  handling, grid construction, merge, pair collection, collision resolution,
  and final apply.
- `CompletePhysicsBenchmark.java`
  Runs the recommended automatic comparison across the sequential,
  platform-threaded, and task-based physics engines. It uses the same
  deterministic scenarios, warmup, repeat count, and checksum validation for
  every implementation.

## Headless simulation runner

The headless runner is the preferred benchmark entry point when the goal is to
compare the simulation logic without Swing rendering:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.HeadlessSimulationRunner sequential 100 1 600 0
java -cp assignment-1/target/classes pcd.poool.benchmark.HeadlessSimulationRunner threads 1000 8 600 42
java -cp assignment-1/target/classes pcd.poool.benchmark.HeadlessSimulationRunner executor 5000 8 600 42
```

Arguments are:

```text
implementation_type balls_count thread_count simulation_steps random_seed
```

The runner keeps GUI code out of the benchmark path and returns a final state
hash so repeated runs of the same scenario can be validated.

All benchmark entry points now consume the shared `BenchmarkConfig` model, so
defaults, validation, and exported configuration values stay centralized.
The measurement infrastructure is shared through `BenchmarkRunner`, which
keeps warmup runs, measured runs, raw per-run results, and summary statistics
separate.
`BenchmarkCsvWriter` writes `benchmark-runs.csv` and `benchmark-summary.csv`
with stable headers so the results can be fed directly into charts or report
tables.

To execute the full benchmark matrix in one command:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.BenchmarkSuite
```

The suite stores its output in a timestamped directory under
`benchmarks/results/`, for example `benchmarks/results/20260621-131530-000/`.

## Recommended comparison

Run the complete benchmark from the repository root after compiling:

```bash
mvn -f assignment-1/pom.xml package
java -cp assignment-1/target/classes pcd.poool.benchmark.CompletePhysicsBenchmark 30 5 2
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
across repeats but produced a different final floating-point trajectory from
the sequential immediate-resolution baseline; in that case gameplay rule tests
should be used together with timing data. The task-based engine uses
deterministic collision rounds for small contact sets and a parallel
accumulated-impulse solver for larger contact sets, so the benchmark output is
the intended source for understanding the consistency/performance tradeoff.

## Relationships

- Uses board configurations from `model.physics.config`.
- Uses `Board`, `GameModel`, `physics.sequential.PhysicsEngine`,
  `physics.threaded.ThreadedPhysicsEngine`, or
  `physics.taskbased.TaskBasedPhysicsEngine` depending on the workload being
  measured.
- Produces execution-time data, but does not affect the main game logic.
