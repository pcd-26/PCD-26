# Selected Benchmark Snapshot

This note records the benchmark snapshot we should reuse in the final report.
It comes from the current stabilized `speedup` run, which focuses on the larger
workload subset and uses the standard warmup/measured window.

## Why this snapshot

The current `speedup-results.csv` shows the best overall speedup for:

- implementation: `threads`
- balls: `2500`
- workers: `16`
- steps: `600`
- seed: `42`

This row has the highest speedup in the available stabilized snapshot:

- sequential median: `433.920800 ms`
- threads median: `230.347500 ms`
- speedup: `1.883766`

That makes it the best single number to cite when we want to show the
shared parallel kernel at its strongest point on the current stabilized run.

## Matching environment

The saved benchmark snapshot was generated on:

- JVM: `OpenJDK 64-Bit Server VM 17.0.19+10`
- OS: `Windows 11 10.0 amd64`
- available processors reported by the benchmark suite: `16`

## Files to reuse in the report

- `benchmarks/results/speedup-results.csv`
- `benchmarks/results/aggregated-results.csv`
- `benchmarks/results/environment.csv`
- `benchmarks/charts/speedup-vs-balls.png`
- `benchmarks/charts/speedup-vs-workers.png`

## Report usage note

Use this snapshot as the main performance example when the report needs a
single representative benchmark. Keep the comparison framed as median
behavior on the same machine/JVM pair, not as a one-off best run claim.
