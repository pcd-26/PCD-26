# Local benchmark scripts

This directory contains the cross-platform entry points for the benchmark
workflow.

## Local benchmark runner

Use the Python wrapper as the preferred way to run the full benchmark flow on
Windows or Linux:

```bash
python scripts/run_benchmarks.py
```

The script:

- builds `assignment-1` with Maven
- runs the Java benchmark pipeline in `full` mode by default
- writes benchmark CSV results under `benchmarks/results/`
- refreshes `benchmarks/charts/`
- keeps only the latest chart version by clearing the chart directory before
  generating a new set

Use `--mode smoke` only for the reduced suite. In that case the wrapper
produces benchmark results but does not regenerate the chart set.

Use these options when needed:

```text
--mode full|smoke
--results-root benchmarks/results
--charts-root benchmarks/charts
--skip-build
--maven-goal compile
```

If you only need to regenerate charts from an existing benchmark snapshot, run
the plotter directly:

```bash
python scripts/plot_benchmarks.py --input-dir benchmarks/results --output-dir benchmarks/charts
```

The plotter also clears the target chart directory before writing the new PNG
and SVG files, so the folder still keeps only the latest chart set.
