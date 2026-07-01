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
- `pcd.poool.benchmark.GuiResponsivenessBenchmarkRunner`
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
- `GuiResponsivenessBenchmarkRunner` benchmarks all three engines.

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

Scalability aggregated output currently reports:

- mean, median, and standard deviation for elapsed time
- mean, median, and standard deviation for throughput
- mean, median, and standard deviation for coordination time
- mean, median, and standard deviation for coordination ratio
- mean tasks submitted

GUI raw runs currently collect:

- average frame time
- 95th percentile frame time
- maximum frame time
- average frames per second
- frames above 16 ms
- frames above 33 ms
- `jvm`
- `os`
- `availableProcessors`

GUI aggregated output currently reports:

- mean and median frame time
- 95th percentile frame time
- maximum frame time
- mean and median FPS
- mean frames above 16 ms
- mean frames above 33 ms

Derived analysis tables are also produced from `benchmark-summary.csv`:

- `speedup-table.csv`
- `efficiency-table.csv`
- `scalability-table.csv`

### Generated result files and formats

The repository currently contains both the newer pipeline layout and the older
legacy suite layout.

Current pipeline outputs:

- `benchmarks/results/run-<timestamp>/raw-results.csv`
- `benchmarks/results/run-<timestamp>/aggregated-results.csv`
- `benchmarks/results/run-<timestamp>/speedup-results.csv`
- `benchmarks/results/run-<timestamp>/raw-scalability-results.csv`
- `benchmarks/results/run-<timestamp>/aggregated-scalability-results.csv`
- `benchmarks/results/run-<timestamp>/environment.csv`
- `benchmarks/results/run-<timestamp>/benchmark-runtime-metadata.csv`
- `benchmarks/results/run-<timestamp>/avg-tick-time-by-engine.csv`
- `benchmarks/results/run-<timestamp>/throughput-by-engine.csv`
- `benchmarks/results/run-<timestamp>/speedup-by-worker-count.csv`
- `benchmarks/results/run-<timestamp>/efficiency-by-worker-count.csv`
- `benchmarks/results/run-<timestamp>/crossover-workloads.csv`

Legacy suite outputs still supported by the code:

- `benchmarks/results/benchmark-runs.csv`
- `benchmarks/results/benchmark-summary.csv`

Optional GUI outputs, if the dedicated GUI runner is executed directly:

- `benchmarks/results/raw-gui-results.csv`
- `benchmarks/results/aggregated-gui-results.csv`

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

Warmup exists in the current suite.

- Default headless, scalability, and GUI runners use 2 warmup runs and 5 measured runs.
- `BenchmarkSuite` uses the same 2 warmup / 5 measured pattern in full mode.
- Smoke mode in `BenchmarkSuite` reduces the matrix to 1 warmup and 1 measured run.

Deterministic seeds are used, but not uniformly across every entry point.

- The direct headless, scalability, and GUI runners default to seed `42`.
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
CLI benchmark runs now write into timestamped `run-<timestamp>/` subdirectories
so repeated executions do not overwrite earlier snapshots unless a caller
explicitly points a benchmark command at the same output directory.

## Known benchmark gaps

- Setup and initialization time are not measured, so the results only capture
  the runtime loop, not the full end-to-end cost of starting a scenario.
- The default pipeline does not execute the GUI benchmark family, so GUI
  responsiveness is not part of the standard local report.
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

As a result, current results are only comparable when the implementation,
scenario size, steps, seed, warmup policy, and benchmark family are all the
same. Comparisons across different result layouts, different seeds, or
different benchmark families should be treated as exploratory rather than
apples-to-apples.
