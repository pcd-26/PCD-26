# Assignment 1 Benchmark Audit

This document records the benchmark suite as it exists in the repository today.
It is an audit of the current entry points, outputs, and measurement choices,
not a proposal for the next benchmark design.

## Current benchmark suite

### Entry points

The benchmark code currently exposes these command-line entry points:

- `pcd.poool.benchmark.BenchmarkPipeline`, launched by `assignment-1/scripts/run_benchmarks.py`
- `pcd.poool.benchmark.BenchmarkSuite`
- `pcd.poool.benchmark.HeadlessBenchmarkRunner`
- `pcd.poool.benchmark.ScalabilityBenchmarkRunner`
- `pcd.poool.benchmark.BenchmarkScalabilityAnalyzer`
- `assignment-1/scripts/plot_benchmarks.py` for chart generation from an existing results snapshot

The default local workflow is the Python wrapper plus `BenchmarkPipeline`.
That pipeline runs the headless comparison suite, the benchmark suite, the
scalability suite, and chart generation in one pass.

### Benchmarked engines

The benchmark suite currently compares these execution strategies:

- `sequential`
- `threads`
- `executor`

Implementation-wise, those correspond to the sequential physics engine, the
threaded physics engine, and the task-based physics engine.

Current coverage by benchmark family:

- `HeadlessBenchmarkRunner` benchmarks all three engines.
- `BenchmarkSuite` benchmarks all three engines.
- `ScalabilityBenchmarkRunner` benchmarks only `threads` and `executor`.

The primary speedup runs are now strict: they time only the simulation loop and
keep per-step profiling disabled so the benchmark does not add coordination
overhead to the measured path.

### Metrics currently collected

Headless raw runs currently collect:

- `elapsedMillis`
- `throughputStepsPerSec`
- `cpuUtilizationPercent`
- `checksum`
- `status`
- `failureReason`
- `syncTimeMillis`
- `aggregationTimeMillis`
- `taskSubmissionTimeMillis`
- `joinOrFutureWaitMillis`
- `lockAcquisitions`
- `submittedTasks`
- `stateReadTimeMillis`
- `partitionTimeMillis`
- `movementTimeMillis`
- `holeInteractionTimeMillis`
- `collisionDetectionTimeMillis`
- `collisionResolutionTimeMillis`
- `mergeApplyTimeMillis`
- `jvm`
- `os`
- `availableProcessors`

Headless summary output currently reports:

- mean, median, min, max, and standard deviation for elapsed time
- mean and median throughput
- mean and median CPU utilization
- speedup
- efficiency

Scalability raw runs currently collect:

- `elapsedMs`
- `throughput`
- `coordinationMs`
- `coordinationRatio`
- `tasksSubmitted`
- `jvm`
- `os`
- `availableProcessors`

In strict speedup mode, the coordination fields are retained for CSV
compatibility but remain zero because profiling is disabled during the measured
run.

Scalability aggregated output currently reports:

- mean, median, and standard deviation for elapsed time
- mean, median, and standard deviation for throughput
- mean, median, and standard deviation for coordination time
- mean, median, and standard deviation for coordination ratio
- mean tasks submitted

Derived analysis tables are also produced from `benchmark-summary.csv`:

- `speedup-table.csv`
- `efficiency-table.csv`
- `scalability-table.csv`

### Generated result files and formats

The repository currently contains both the newer pipeline layout and the older
legacy suite layout.

Current pipeline outputs:

- `benchmarks/results/raw-results.csv`
- `benchmarks/results/aggregated-results.csv`
- `benchmarks/results/speedup-results.csv`
- `benchmarks/results/raw-scalability-results.csv`
- `benchmarks/results/aggregated-scalability-results.csv`
- `benchmarks/results/environment.csv`
- `benchmarks/results/benchmark-runtime-metadata.csv`
- `benchmarks/results/avg-tick-time-by-engine.csv`
- `benchmarks/results/throughput-by-engine.csv`
- `benchmarks/results/speedup-by-worker-count.csv`
- `benchmarks/results/efficiency-by-worker-count.csv`
- `benchmarks/results/crossover-workloads.csv`

Legacy suite outputs still supported by the code:

- `benchmarks/results/benchmark-runs.csv`
- `benchmarks/results/benchmark-summary.csv`

Chart outputs are written as paired image files:

- `benchmarks/charts/*.png`
- `benchmarks/charts/*.svg`

### Setup cost, warmup, and seeds

Setup cost is currently not part of the timed section. The runners create the
board, seed it, and attach the engine before calling `BenchmarkRunner.time(...)`
on the actual simulation or GUI loop. The measured window therefore excludes:

- board construction
- engine construction
- `board.init(...)`
- CSV initialization
- runtime telemetry capture

The benchmark runners also buffer raw rows in memory and write CSV files after
the measured runs, so filesystem I/O stays outside the benchmarked loop.

Warmup exists in the current suite.

- Default headless, scalability, and GUI runners use 2 warmup runs and 5 measured runs.
- `BenchmarkSuite` uses the same 2 warmup / 5 measured pattern in full mode.
- Smoke mode in `BenchmarkSuite` reduces the matrix to 1 warmup and 1 measured run.
- Default headless and scalability runners use 2 warmup runs and 5 measured runs.

Deterministic seeds are used, but not uniformly across every entry point.

- The direct headless and scalability runners default to seed `42`.
- The legacy `BenchmarkSuite` matrix uses the default configuration seed `0`.
- Every runner also accepts an explicit `--seed` override.

### Initial-state equivalence

For a given scenario, the engines start from equivalent initial board states.
Each runner builds the board with `SeededBenchmarkBoardConf(balls, seed)`,
which uses the same deterministic placement logic for all implementations.

The benchmark correctness guard also compares non-sequential runs against the
sequential baseline for the same `balls`, `steps`, and `seed`.

The important caveat is that the checked-in result snapshots were generated by
different entry points and therefore do not all share the same defaults. In
particular, the legacy `benchmark-*.csv` files and the newer `raw-*.csv` files
are not directly comparable without checking the seed, steps, and execution
family first.
CLI benchmark runs now clear and repopulate the configured `results` and
`charts` directories on each execution, so repeated runs replace the previous
snapshot in place.

## Benchmark methodology

### Goals

The benchmark suite exists to compare the physics engines under controlled,
repeatable workloads. It is intended to answer:

- how much time each engine spends per tick
- how much throughput each engine sustains
- how worker count changes speedup and efficiency
- which workloads are the first ones where a parallel engine becomes faster
  than the sequential baseline

The suite is not meant to support performance claims without benchmark data.
Any statement about one engine being faster, more efficient, or more scalable
must be backed by the exported benchmark results.

### Workloads

Benchmark workloads are deterministic and are defined by:

- board size
- number of balls
- number of ticks
- seed
- collision profile

Workload sizes currently used for comparisons:

- `small`
- `medium`
- `large`

Collision scenarios currently used for comparisons:

- `low-collision`
- `high-collision`

Each workload definition is shared across engines so that all engines start
from the same initial state for the same scenario.

### Worker-count matrix

Parallel engines are benchmarked with a fixed worker-count matrix:

- `1`
- `2`
- `4`
- `8`
- `available processors`
- `available processors + 1`

Duplicate counts are removed after resolving the runtime processor count, and
invalid counts are skipped. The sequential engine is always kept as the
baseline for the same workload.

### Warmup and measurement

The suite separates warmup from measured iterations.

- warmup runs are executed first and are not included in summary statistics
- measured runs are executed after warmup and each measured tick contributes
  one timing sample
- setup work such as workload creation, engine creation, and result export is
  outside the timed section

### Seeds and initial state

Deterministic seeds are used for all workloads. Re-running the same workload
definition with the same seed produces the same initial board state.

For any benchmark result to be comparable, the engines must start from
equivalent initial states. The benchmark infrastructure uses the same seeded
workload definition for sequential, threaded, and task-based engines.

### Collected metrics

The benchmark export captures the following metrics for each run or summary:

- engine name
- board size
- number of balls
- number of ticks
- worker count
- average tick time
- min tick time
- max tick time
- standard deviation
- throughput
- speedup vs sequential
- efficiency
- final-state checksum

When available, the export also includes `p95` latency summaries.

### Formulas

The benchmark uses these definitions:

- average tick time = mean measured tick duration
- throughput = measured ticks per second
- speedup = sequential average tick time / engine average tick time
- efficiency = speedup / worker count
- crossover = smallest workload where a parallel engine has speedup > `1`

Speedup is always computed against the matching sequential baseline for the
same workload size, collision profile, board size, ball count, tick count, and
seed.

### Runtime metadata

Each exported run snapshot includes runtime metadata:

- timestamp
- operating system
- Java version
- JVM name
- available processors
- max memory
- benchmark configuration
- git commit hash when available

### Validation tests

The benchmark infrastructure is covered by deterministic tests that verify:

- deterministic workload generation
- equivalent initial states across engines
- warmup samples are excluded from summary statistics
- worker-count matrix generation
- metric calculations on synthetic data
- CSV export validity
- summary table generation
- expensive benchmark paths remain separated from normal unit tests

## Known benchmark gaps

- Setup and initialization time are not measured, so the results only capture
  the runtime loop, not the full end-to-end cost of starting a scenario.
- Scalability outputs do not currently carry `cpuUtilizationPercent`, so CPU
  usage is less complete there than in the headless benchmark family.
- The headless summary CSV drops some raw-run detail, including the final state
  hash and the per-run synchronization breakdown.
- The summary CSV does not retain throughput standard deviation or CPU
  utilization standard deviation, even though those values exist in the raw
  headless aggregation.
- Current result snapshots mix legacy and newer CSV layouts, which makes casual
  file-to-file comparisons easy to misread.
- The repository now relies on deterministic seeds, but the default seed is not
  the same across every benchmark entry point, so users still need to check the
  exact command line before comparing snapshots.
- The current data model still estimates coordination cost rather than
  separating every synchronization phase into a first-class benchmark metric.
- Performance claims still depend on the exact workload, seed, warmup policy,
  and engine family used in the run.

As a result, current results are only comparable when the implementation,
scenario size, steps, seed, warmup policy, and benchmark family are all the
same. Comparisons across different result layouts, different seeds, or
different benchmark families should be treated as exploratory rather than
apples-to-apples.
