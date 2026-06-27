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
  throughput, checksum, synchronization metrics, and status.
- `BenchmarkSummary.java`
  Aggregate statistics for a benchmark session, kept separate from the raw
  per-run measurements.
- `BenchmarkCsvWriter.java`
  Appends raw runs and aggregate summaries to stable CSV files in the
  configured output directory. Raw run rows include the synchronization
  overhead columns used by the benchmark report.
- `BenchmarkScalabilityAnalyzer.java`
  Reads `benchmark-summary.csv` and writes `speedup-table.csv`,
  `efficiency-table.csv`, and `scalability-table.csv` for report-ready
  post-processing.
- `scripts/plot_benchmarks.py`
  Generates report-ready PNG and SVG charts from the benchmark CSV files and
  stores them in `benchmarks/charts/report/`.
- `BenchmarkSuite.java`
  Executes the full benchmark matrix or the lightweight CI smoke matrix,
  prints progress, and stores all results in a timestamped directory under
  `benchmarks/results/` or `benchmarks/results/ci/`.
- `RuntimeTelemetry.java`
  Captures JVM, OS, heap, CPU-count, and optional process CPU-time metadata
  for benchmark interpretation.
- `RuntimeTelemetryCsvWriter.java`
  Exports the telemetry snapshot to `environment.csv` alongside benchmark
  results.
- `HeadlessSimulationRunner.java`
  Runs a seeded simulation without GUI rendering and reports elapsed time,
  completed steps, a final board-state hash, and optional coordination
  metrics for the selected execution strategy.
- `HeadlessBenchmarkRunner.java`
  Runs the reproducible comparison benchmark for sequential, threaded, and
  executor-based simulations, measures only the simulation loop, and writes
  raw CSV rows to `benchmark/results/raw-results.csv`. It also triggers the
  derived aggregation step that writes `benchmark/results/aggregated-results.csv`
  and `benchmark/results/speedup-results.csv`.
- `ScalabilityBenchmarkRunner.java`
  Runs the worker-count scalability benchmark for the threaded and executor
  implementations, using the medium and heavy workloads, and writes raw CSV
  rows to `benchmark/results/raw-scalability-results.csv` plus aggregated rows
  to `benchmark/results/aggregated-scalability-results.csv`.
- `GuiResponsivenessBenchmark.java`
  Runs a scripted Swing benchmark that measures render latency, EDT delay, and
  update rate separately from headless throughput.
- `GuiResponsivenessMonitor.java`
  Collects GUI timing metrics for request, EDT, and render completion phases.
- `GuiResponsivenessCsvWriter.java`
  Exports GUI responsiveness measurements to `gui-responsiveness.csv`.
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

## Headless benchmark runner

The headless benchmark runner compares the three simulation implementations in
one command and writes a raw CSV file without any GUI rendering overhead:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.HeadlessBenchmarkRunner
```

By default it benchmarks ball counts `100, 500, 1000, 2500, 5000, 10000` with
seed `42`, at least two warmup runs, and at least five measured runs. The raw
output is written to `benchmark/results/raw-results.csv`.

Supported options are:

```text
--implementation all|sequential|threads|executor
--balls 100,500,...
--steps N
--seed N
--workers N
--warmup N
--measured N
--output benchmark/results/raw-results.csv
```

The CSV rows contain the implementation, ball count, worker count, step count,
seed, run index, warmup flag, elapsed milliseconds, throughput, coordination
milliseconds, coordination ratio, submitted task count, final state hash, JVM
identification, operating-system identification, and available CPU count.

After the raw export, the post-processor groups rows by
`implementation+balls+workers+steps+seed`, computes average and standard
deviation for elapsed time, throughput, and coordination metrics, and then
derives speedup rows using the sequential baseline for the same `balls`,
`steps`, and `seed`.

## Scalability benchmark

The scalability runner focuses on worker-count scaling for the concurrent
implementations only:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.ScalabilityBenchmarkRunner
```

By default it measures the `2500`-ball medium workload and the `10000`-ball
heavy workload with worker counts `1, 2, 4, 8, availableProcessors,`
and `availableProcessors + 1`. The benchmark uses a fixed step count, seed,
warmup runs, and measured runs so repeated executions stay comparable.

Supported options mirror the headless runner, with `--workers` accepting a
comma-separated worker list and `--implementation` limited to `threads`,
`executor`, or `all`.

All benchmark entry points now consume the shared `BenchmarkConfig` model, so
defaults, validation, and exported configuration values stay centralized.
The measurement infrastructure is shared through `BenchmarkRunner`, which
keeps warmup runs, measured runs, raw per-run results, and summary statistics
separate.
`BenchmarkCsvWriter` writes `benchmark-runs.csv` and `benchmark-summary.csv`
with stable headers so the results can be fed directly into charts or report
tables. The raw run export includes the `syncTimeMillis`,
`aggregationTimeMillis`, `taskSubmissionTimeMillis`,
`joinOrFutureWaitMillis`, `lockAcquisitions`, and `submittedTasks` columns
when instrumentation is enabled. Failed runs are exported with `status=FAILED`
and a `failureReason` column so correctness problems remain visible in the raw
data.
The full benchmark suite also checks that sequential, threaded, and
executor-based runs agree on the same scenario before their results are used
for aggregate comparisons.
Both benchmark runners export per-run coordination estimates in addition to
elapsed time and throughput, so the derived CSVs can report average
coordination cost and ratio alongside the existing timing metrics.
`RuntimeTelemetryCsvWriter` writes `environment.csv` with one stable header and
one snapshot row so the benchmark report can state the runtime conditions.
`GuiResponsivenessCsvWriter` writes `gui-responsiveness.csv` with the GUI
latency, delay, and update-rate measurements collected by the Swing benchmark.
`BenchmarkScalabilityAnalyzer` consumes `benchmark-summary.csv` and produces
speedup, efficiency, and scalability tables that can be copied directly into
the report.
`scripts/plot_benchmarks.py` turns the CSV exports into charts for execution
time, throughput, speedup, efficiency, CPU utilization, coordination
overhead, and GUI latency.
The GitHub Actions workflows are split into two lanes: the tests workflow
handles Maven verification on pull requests and pushes, and the benchmark
workflow runs the benchmark suite, generates the derived scalability tables,
and uploads the report-ready chart set as a separate timestamped artifact.
The chart workflow also packages the report figures into a separate
assignment-specific zip and publishes it to the shared `latest` GitHub Release
alongside the delivery zip. The release uses fixed asset names, so every new
run replaces the previous `Assignment-01-latest.zip` and
`Assignment-01-benchmark-charts-latest.zip` files instead of adding dated
copies. The report charts stay outside the assignment zip under
`benchmarks/charts/report/` and can be reused later when preparing the final
document. CI benchmark numbers are only for regression checks; the report
should use locally or controlled-machine generated results.

To execute the full benchmark matrix in one command:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.BenchmarkSuite
```

To execute the CI smoke matrix locally:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.BenchmarkSuite --smoke benchmarks/results/ci
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
