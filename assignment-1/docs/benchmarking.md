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
