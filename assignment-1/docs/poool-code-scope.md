# Poool Final-Delivery Scope

`pcd.poool` contains only code intended for final delivery.

## Included
- `pcd.poool.model.common.math`: `P2d`, `V2d`
- `pcd.poool.model.physics`: `Boundary`, `Hole`, `Ball`, `Board`, `BoardConf`,
  `PhysicsDefaults`, `PhysicsStepper`, `PhysicsEngine`,
  `ThreadedPhysicsEngine`, `SpatialCollisionDetector`, `PhysicsWorker`,
  `WorkerCompletionMonitor`
- `pcd.poool.model.physics.config`: `MinimalBoardConf`,
  `StandardGameBoardConf`, `LargeBoardConf`, `ThousandBallsBoardConf`,
  `MassiveBoardConf`
- `pcd.poool.model.game`: `GameModel`, `GameSnapshot`, `Player`,
  `GameStatus`, `GameOverReason`
- `pcd.poool.model.concurrent`: `BoundedBuffer`, `BoundedBufferImpl`
- `pcd.poool.view`: `RenderSynch`
- `pcd.poool.view.board`: `ViewModel`, `View`, `ViewFrame`
- `pcd.poool.controller`: `Cmd`, `ActiveController`
- `pcd.poool.threaded`: `ThreadedGameRunner`, `ThreadedGameSnapshot`,
  `ThreadedBotAgent`, `CommandQueueMonitor`, `GameCommand`,
  `CommandReceipt`, `SnapshotStore`
- `pcd.poool.benchmark`: `PhysicsBenchmark`, `SequentialGameBenchmark`,
  `ThreadedPhysicsBenchmark`
- `pcd.poool`: `SequentialPoool`, `ThreadedPoool`

## Excluded from `pcd.poool`
- sketch02 counter demo artifacts
- sketch bootstrap/demo launchers

They remain under `assignment-1/reference/sketch01` and `assignment-1/reference/sketch02`, outside the Maven source tree.

For a component-level explanation of responsibilities and runtime relations,
see [`docs/runtime-architecture.md`](runtime-architecture.md).

