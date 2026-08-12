# Parallel physics performance architecture

## Hot path

The live concurrent tick is:

```text
GameLoop.tick
  -> GameModel.step
    -> Board.updateState
      -> ThreadedPhysicsEngine or TaskBasedPhysicsEngine
        -> ThreadedPhysicsEngine
        -> TaskBasedPhysicsEngine
```

The two public engines are facades. All numerical work is implemented once in
`ThreadedPhysicsEngine` and `TaskBasedPhysicsEngine`; the core physics rules
stay the same while the worker strategy changes.

## Parallel and serial phases

| Phase | Execution | Reason |
| --- | --- | --- |
| collect active balls | serial | establish one tick-start view |
| integrate movement | parallel ranges | each ball has one owner |
| holes and pocketing | serial | changes shared board membership and events |
| build local grids | parallel ranges | maps are private |
| merge/order grid | serial | stable deterministic order |
| collision contributions | parallel cells | deltas are private |
| merge deltas/contacts | serial | authoritative deterministic commit |
| apply many independent deltas | parallel above threshold | safe per-ball ownership |

## Allocation and lifecycle choices

- Platform workers and Executor pool threads are created once and reused.
- The active-ball list is reused through a thread-local buffer.
- Collision deltas use primitive arrays and sparse touched-index lists.
- Packed `long` contact pairs avoid allocating one object per contact.
- The Executor scheduler keeps ranges below 256 items inline, because task
  creation costs more than the available work there.

## Speedup limit

The serial merge, hole processing, game rules, and snapshot publication bound
the maximum speedup according to Amdahl's law. This is deliberate: removing
those boundaries would trade reproducibility and scoring correctness for
fragile fine-grained mutation.

## Verification gate

Performance changes are accepted only when:

1. `mvn -f assignment-1/pom.xml test` passes;
2. seeded fingerprints match across implementations;
3. `python assignment-1/scripts/run_benchmarks.py --mode speedup` shows no
   material regression on the same machine/JVM;
4. the scalability run continues to exercise multiple worker counts.

The refactoring to the shared kernel improved the measured concurrent medians
in the same-session before/after run for every canonical workload. Sequential
variation remained noisy even though the sequential engine was unchanged, so
speedup ratios must always be interpreted together with absolute medians.
