# Benchmark package

This package contains the Java entry points and helpers used to collect and
post-process benchmark data for `assignment-1`.

## Purpose

The benchmark package is not part of the gameplay runtime. Its role is to run
controlled workloads, export CSV measurements, and feed the report charts used
by the assignment.

## Main components

- `config/`
  Shared configuration types.
- `core/`
  Timing, result, workload, telemetry, and state-fingerprint primitives.
- `engine/`
  Engine adapters and correctness checks.
- `io/`
  CSV writers for raw runs and telemetry.
- `postprocess/`
  Summary exporters and derived-table generators.
- `runner/`
  Command-line benchmark entry points and orchestration.
- `util/`
  Small support helpers such as directory and logging utilities.

## Local workflow

Use the Python wrapper from the repository root:

```bash
python scripts/run_benchmarks.py
```

The wrapper:

- compiles `assignment-1`
- runs the Java benchmark pipeline
- writes benchmark results under `benchmarks/results/`
- exports `environment.csv` with maximum JVM-visible threads, JVM, and OS
- refreshes `benchmarks/charts/`
- clears `benchmarks/charts/` before every run so only the latest chart set is
  kept
- uses medians as the primary latency-oriented summary instead of emphasizing
  a single best-case run
- runs only the headless and scalability benchmark families in the local Python-driven flow
- uses a standard `full` matrix capped at `2500` balls for manageable local
  execution time

If you only need to regenerate charts from an existing snapshot, run the chart
generator directly:

```bash
python scripts/plot_benchmarks.py --input-dir benchmarks/results --output-dir benchmarks/charts
```

The chart generator supports both the current benchmark layout and older
legacy result snapshots. In both cases it writes the latest chart set directly
into the chosen output directory after clearing it, so only the latest PNG and
SVG files are kept. When `environment.csv` is available, the generator also
prints the key benchmark machine specs inside every chart.
