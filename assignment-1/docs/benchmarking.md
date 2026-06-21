# Assignment 1 - Benchmarking Requirements and Metrics Model

## 1. Purpose

This document defines the benchmark model used to compare the three execution
strategies in `pcd.poool`:

- `sequential`
- `threads`
- `executor`

The benchmark suite must answer two separate questions:

1. How much faster or slower is each execution strategy for the same
   deterministic workload?
2. How well does each strategy preserve responsiveness when the GUI is involved?

The benchmark design must therefore keep headless performance measurements and
GUI responsiveness measurements separate.

## 2. Benchmark principles

All benchmark runs must satisfy the same baseline conditions:

- same deterministic initial board state
- same command trace or scripted control sequence
- same warmup count and measured-run count
- same JVM and machine for one comparison set
- same physical scenario for all implementations being compared
- same ball count and thread count when a thread count is part of the scenario

The goal is comparability, not only raw speed. A slower implementation is still
useful if it provides better responsiveness or lower coordination cost for a
given workload.

## 3. Metric definitions

### 3.1 Execution time

Execution time is the wall-clock duration required to complete a benchmarked
workload.

```text
execution_time_seconds = end_time - start_time
```

For repeated runs, report at least:

- average execution time
- minimum execution time
- maximum execution time
- standard deviation

### 3.2 Throughput

Throughput measures how many simulation steps are completed per second.

```text
throughput = simulation_steps / elapsed_seconds
```

Units:

- `simulation_steps` in steps
- `elapsed_seconds` in seconds
- `throughput` in steps/second

Throughput is the preferred metric for headless physics and game-loop
benchmarks.

### 3.3 Speedup

Speedup compares a concurrent implementation against the sequential baseline.

```text
speedup = sequential_time / concurrent_time
```

Interpretation:

- `speedup > 1.0` means the concurrent version is faster
- `speedup = 1.0` means parity
- `speedup < 1.0` means the concurrent version is slower

The sequential baseline must be measured using the same scenario and the same
number of simulation steps.

### 3.4 Efficiency

Efficiency measures how effectively the available worker threads are used.

```text
efficiency = speedup / thread_count
```

Interpretation:

- `1.0` is ideal linear scaling
- values below `1.0` indicate parallel overhead or resource contention

When `thread_count = 1`, efficiency should be treated as a baseline reference
rather than as a meaningful parallel-scaling result.

### 3.5 CPU utilization

CPU utilization estimates how much of the available CPU capacity is consumed by
the benchmark.

```text
cpu_utilization = process_cpu_time / (elapsed_seconds * logical_cpu_count)
```

If the result is reported as a percentage:

```text
cpu_utilization_percent = cpu_utilization * 100
```

Use the same definition for sequential, threaded, and executor-based runs.
When platform support allows it, process CPU time is preferred over per-thread
sampling because it is more stable and easier to compare.

### 3.6 Synchronization overhead

Synchronization overhead captures the time spent coordinating workers rather
than performing useful simulation work.

```text
synchronization_overhead = synchronization_time / elapsed_seconds
```

The `synchronization_time` component includes, when measurable:

- monitor acquisition and release
- queue operations
- barrier waits
- merge phases
- snapshot publication
- worker wake-up and join time

This metric is especially important for the `threads` and `executor`
implementations because it explains why a concurrent version may be slower on
small workloads.

### 3.7 GUI responsiveness

GUI responsiveness measures how quickly the interface reflects a user-visible
event.

For a scripted benchmark, define:

```text
gui_responsiveness_latency = visible_update_time - input_event_time
```

Report this as:

- average latency
- median latency
- 95th percentile latency
- worst-case latency

For GUI benchmarks, responsiveness is more important than raw throughput.
Throughput can still be reported, but it must not be mixed with headless values
unless the renderer and interaction path are the same.

## 4. Benchmark families

### 4.1 Headless benchmarks

Headless benchmarks run without Swing rendering. They measure:

- physics step time
- total simulation time
- throughput
- speedup
- efficiency
- CPU utilization
- synchronization overhead

These runs are the primary source for comparing `sequential`, `threads`, and
`executor`.

### 4.2 GUI benchmarks

GUI benchmarks include rendering and event handling. They measure:

- frame latency
- GUI responsiveness
- end-to-end interaction time
- optional frame rate

GUI benchmarks must be treated as a separate family because rendering costs can
dominate the physics cost and make throughput numbers hard to interpret.

GUI benchmark exports should be written to a separate file, such as
`gui-responsiveness.csv`, and should report at least:

- average update interval
- average update latency
- maximum update delay
- update rate or FPS
- EDT delay measured with `SwingUtilities.invokeLater`
- delayed update count when a threshold is configured

## 5. Benchmark matrix

The benchmark matrix defines the supported comparison space.

### 5.1 Implementations

- `sequential`
- `threads`
- `executor`

`executor` is the task-based implementation backed by the Executor Framework.

### 5.2 Ball counts

- `100`
- `500`
- `1000`
- `2000`
- `5000`

These values define the workload scale. The benchmark scenarios must be able to
generate deterministic boards matching these counts.

### 5.3 Thread counts

- `1`
- `2`
- `4`
- `8`
- `availableProcessors`

The `availableProcessors` option means the logical CPU count reported by the
JVM at runtime. It is not a fixed number in the specification and must be
resolved on the benchmark machine.

### 5.4 Warmup and measured runs

- warmup runs: `2`
- measured runs: `5`

Warmup runs are excluded from the reported metrics. They are used to let the JIT
compiler stabilize and to reduce one-time class-loading effects.

## 6. Explicit benchmark scenarios

The benchmark scenarios must be listed explicitly in the report and in any
benchmark output.

Recommended scenario set:

1. `S1 - 100 balls`
   - low-load scenario
   - used to measure coordination overhead and GUI responsiveness

2. `S2 - 500 balls`
   - medium-load scenario
   - used to measure the transition point where parallelism starts to matter

3. `S3 - 1000 balls`
   - standard stress scenario
   - used to compare the main implementation strategies under heavier load

4. `S4 - 2000 balls`
   - high-load scenario
   - used to test worker scaling and synchronization cost

5. `S5 - 5000 balls`
   - massive scenario
   - used to test the upper end of throughput and CPU utilization

If the implementation uses named board profiles, they should be mapped to these
count-based scenarios so that the report remains easy to interpret.

## 7. Measurement protocol

Each benchmark case should follow the same protocol:

1. initialize the deterministic scenario
2. run `2` warmup executions
3. run `5` measured executions
4. record per-run execution time
5. compute average, min, max, and standard deviation
6. for concurrent runs, record thread count and CPU utilization
7. for GUI runs, record responsiveness metrics separately from headless data

The benchmark should keep the command trace and physics initialization stable so
that differences between runs are attributable to the execution strategy and
not to scenario randomness.

## 8. Reporting rules

Benchmark reports should present:

- scenario name
- implementation name
- thread count, when applicable
- average execution time
- throughput
- speedup relative to sequential
- efficiency
- CPU utilization
- synchronization overhead
- GUI responsiveness, for GUI runs

The report must clearly state whether a measurement comes from a headless run
or a GUI run.

Benchmark correctness should be checked on the same scenario across
`sequential`, `threads`, and `executor` runs. When exact checksum equality is
not sufficient, the benchmark should fall back to deterministic state
invariants and clearly mark invalid or mismatched runs as failed. Failed runs
must be excluded from aggregate statistics, but their failure reasons should
still be exported for traceability.

For post-processing, the benchmark results can be analyzed from
`benchmark-summary.csv` to produce:

- `speedup-table.csv`
- `efficiency-table.csv`
- `scalability-table.csv`

These tables are intended for direct inclusion in the report.

The chart-generation script can then turn the benchmark CSV files into PNG
figures for execution time, throughput, speedup, efficiency, CPU utilization,
synchronization overhead, and GUI latency.

## 9. Interpretation guidance

- `sequential` is the semantic baseline.
- `threads` should be expected to outperform `sequential` mostly on larger
  workloads.
- `executor` should be expected to be competitive with `threads` when task
  granularity is appropriate.
- A lower throughput on small workloads does not invalidate the concurrent
  architecture if the same design improves responsiveness or scales better on
  large workloads.
- High synchronization overhead usually means that the workload is too small or
  the task partitioning is too fine-grained.

## 10. Traceability to the assignment goals

This benchmark model supports the assignment requirements by:

- defining benchmark metrics clearly
- making `sequential`, `threads`, and `executor` comparable
- documenting speedup, efficiency, and throughput formulas
- separating headless and GUI benchmarks
- listing explicit reproducible benchmark scenarios

## 11. Benchmark execution guide

This section documents how to run the benchmark workflow from the command line
and how to reproduce the generated artifacts.

### 11.1 Build the project

Compile the assignment before running any benchmark entry point:

```bash
mvn -f assignment-1/pom.xml package
```

### 11.2 Run the headless simulation benchmark

Use the headless runner when you want to benchmark simulation logic without
opening the GUI:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.HeadlessSimulationRunner sequential 100 1 600 0
java -cp assignment-1/target/classes pcd.poool.benchmark.HeadlessSimulationRunner threads 1000 8 600 42
java -cp assignment-1/target/classes pcd.poool.benchmark.HeadlessSimulationRunner executor 5000 8 600 42
```

Command arguments are:

```text
implementation_type balls_count thread_count simulation_steps random_seed
```

The headless runner reports elapsed time, completed steps, and a final
checksum or state hash so that the same scenario can be replayed and checked
for correctness.

### 11.3 Run the GUI benchmark

Use the GUI benchmark when you want responsiveness measurements that include
rendering and event handling:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.GuiResponsivenessBenchmark
```

Do not interact with the GUI during the benchmark run unless the scenario
explicitly asks for user input. GUI benchmarks are intentionally separate from
headless throughput measurements.

### 11.4 Run the full benchmark matrix

Use the benchmark suite to execute the whole matrix in one command:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.BenchmarkSuite
```

The suite runs the sequential, threaded, and executor implementations across
the configured ball counts and thread counts, prints progress, and stores the
results in a timestamped directory under `benchmarks/results/`.

### 11.5 Generate the analysis tables

After the suite has produced `benchmark-summary.csv`, generate report-ready
tables with:

```bash
java -cp assignment-1/target/classes pcd.poool.benchmark.BenchmarkScalabilityAnalyzer benchmarks/results/<timestamp>
```

This writes:

- `speedup-table.csv`
- `efficiency-table.csv`
- `scalability-table.csv`

### 11.6 Generate the charts

After the benchmark CSV files are available, generate the charts with:

```bash
python scripts/plot_benchmarks.py --input-dir benchmarks/results/<timestamp> --output-dir benchmarks/charts
```

This script reads:

- `benchmark-summary.csv`
- `benchmark-runs.csv`
- `speedup-table.csv`
- `efficiency-table.csv`
- `gui-responsiveness.csv`

It writes the PNG figures used in the report into `benchmarks/charts/`.

### 11.7 Output directory structure

Each benchmark campaign produces a timestamped result directory similar to:

```text
benchmarks/results/20260621-131530-000/
```

Typical contents are:

```text
benchmark-runs.csv
benchmark-summary.csv
environment.csv
gui-responsiveness.csv
speedup-table.csv
efficiency-table.csv
scalability-table.csv
```

The chart generator writes PNG files to:

```text
benchmarks/charts/
```

### 11.8 CSV file meanings

- `benchmark-runs.csv` contains one raw record per warmup or measured run.
- `benchmark-summary.csv` contains the aggregate statistics for each benchmark
  scenario.
- `environment.csv` contains the runtime and machine metadata used to interpret
  the measurements.
- `gui-responsiveness.csv` contains GUI timing data and must not be mixed with
  headless throughput measurements.
- `speedup-table.csv`, `efficiency-table.csv`, and `scalability-table.csv`
  are derived analysis tables for the report.

### 11.9 Benchmark environment fields

The exported `environment.csv` records:

- `availableProcessors`
- `jvmName`
- `jvmVersion`
- `osName`
- `osVersion`
- `osArch`
- `maxMemoryBytes`
- `totalMemoryBytes`
- `freeMemoryBytes`
- `processCpuTimeSupported`
- `processCpuTimeNanos`

`processCpuTimeNanos` is populated only when the JVM exposes process CPU time
through the operating system bean.

### 11.10 Known limitations

- Benchmark results can vary across machines, JVM versions, and operating
  systems.
- GUI responsiveness measurements are more sensitive to desktop load than
  headless throughput measurements.
- Exact checksum equality is not always guaranteed for implementations that
  preserve correctness through different execution orders.
- Short workloads may be dominated by warmup effects or synchronization
  overhead.
- Some operating systems do not expose process CPU time, so CPU utilization may
  be partially unavailable or estimated from the supported metadata.

### 11.11 Reduce measurement noise

- Close heavy background applications before running a benchmark campaign.
- Use the same machine for all implementations you want to compare.
- Keep the laptop connected to power during measurements.
- Avoid interacting with the GUI during GUI benchmark runs.
- Repeat the benchmark campaign if the output shows clear outliers.
- Keep the JVM, OS, and hardware configuration unchanged while comparing one
  benchmark matrix.
