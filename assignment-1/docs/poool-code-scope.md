# Poool final-delivery scope

## Playable product

- launchers and shared GUI lifecycle under `pcd.poool`;
- `runtime`: common game loop, mailbox, bot, configuration, and snapshot;
- `model.game`: scoring and match lifecycle;
- `model.physics.common`: board and physical entities;
- `model.physics.sequential`: reference engine;
- `model.physics.parallel`: shared parallel algorithm;
- `model.physics.threaded`: explicit platform-thread scheduler;
- `model.physics.taskbased`: Executor Framework scheduler;
- `view`: Swing rendering and input translation.

## Supporting material outside the product

- `assignment-1/reference`: original course sketches;
- `pcd.poool.benchmark`: compiled development tooling for the report;
- `assignment-1/verification`: JPF harnesses and configuration;
- `assignment-1/report`: LaTeX report sources;
- `assignment-1/benchmarks`: generated CSV and charts.

Maven compiles benchmark classes so existing scripts keep working, but excludes
`pcd/poool/benchmark/**` from the packaged game JAR. Generic sketch controller
and bounded-buffer examples remain only under `reference/`.

See [`runtime-architecture.md`](runtime-architecture.md) for the product flow.
