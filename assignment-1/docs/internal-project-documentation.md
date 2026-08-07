# Assignment 1 documentation index

The project intentionally keeps one document per concern:

- [`runtime-architecture.md`](runtime-architecture.md): complete application
  flow and the five concepts needed for the oral explanation.
- [`physics-engines-and-concurrency.md`](physics-engines-and-concurrency.md):
  shared physics kernel, phases, and the two schedulers.
- [`concurrent-architecture.md`](concurrent-architecture.md): ownership,
  safety/liveness, and README requirement traceability.
- [`benchmarking.md`](benchmarking.md): reproducible measurement procedure.
- [`performance/physics-architecture-analysis.md`](performance/physics-architecture-analysis.md):
  hot path and Amdahl bottlenecks.
- [`verification/`](verification): Petri Nets and JPF artifacts.
- [`poool-code-scope.md`](poool-code-scope.md): final-delivery boundary.

## Commands

```bash
mvn -f assignment-1/pom.xml test
python assignment-1/scripts/run_benchmarks.py --mode speedup
python assignment-1/verification/jpf/run_jpf.py
```

## Explanation order

Start from `PooolApplication`, then follow `GameLoop -> GameModel -> Board ->
PhysicsEngine`. Explain `CommandMailbox` as the input boundary and
`RangeScheduler` as the only difference between the two parallel engines.
Benchmark and verification packages support the report but are not part of the
playable game artifact.
