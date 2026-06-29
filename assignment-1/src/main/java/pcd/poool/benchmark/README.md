# Benchmark package

This package contains the Java entry points and helpers used to collect and
post-process benchmark data for `assignment-1`.

## Purpose

The benchmark package is not part of the gameplay runtime. Its role is to run
controlled workloads, export CSV measurements, and feed the report charts used
by the assignment.

## Main components

- `BenchmarkConfig.java`
  Shared benchmark configuration model used by all benchmark runners.
- `BenchmarkRunner.java`
  Shared timing and aggregation infrastructure for the benchmark workloads.
- `BenchmarkCsvWriter.java`
  Writes raw runs and aggregate summaries to the configured result directory.
- `BenchmarkSuite.java`
  Executes the full or smoke benchmark matrix locally and stores the output in
  `assignment-1/benchmarks/results/`.
- `BenchmarkPipeline.java`
  Orchestrates the full local benchmark flow. The Python wrapper in
  `assignment-1/scripts/run_benchmarks.py`
  calls this entry point.
- `HeadlessBenchmarkRunner.java`
  Runs the reproducible comparison benchmark for sequential, threaded, and
  executor-based simulations.
- `ScalabilityBenchmarkRunner.java`
  Measures worker-count scaling for the concurrent implementations.
- `GuiResponsivenessBenchmarkRunner.java`
  Measures GUI responsiveness separately from the headless benchmark family.
- `BenchmarkScalabilityAnalyzer.java`
  Derives report tables from benchmark summaries when needed.
- `RuntimeTelemetryCsvWriter.java`
  Exports runtime and environment metadata alongside the measurements.

## Local workflow

Use the Python wrapper from the repository root:

```bash
python scripts/run_benchmarks.py
```

The wrapper:

- compiles `assignment-1`
- runs the Java benchmark pipeline
- writes benchmark results under `benchmarks/results/`
- exports `environment.csv` with CPU model, physical cores, logical threads,
  JVM-visible processors, JVM, OS, and RAM
- refreshes `benchmarks/charts/`
- clears `benchmarks/charts/` before every run so only the latest chart set is
  kept

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
