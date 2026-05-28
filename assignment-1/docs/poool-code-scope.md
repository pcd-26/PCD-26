# Poool Final-Delivery Scope

`pcd.poool` contains only code intended for final delivery.

## Included
- `pcd.poool.model.common.math`: `P2d`, `V2d`
- `pcd.poool.model.physics`: `Boundary`, `Hole`, `Ball`, `Board`, `BoardConf`, `PhysicsDefaults`, `PhysicsEngine`, `SpatialCollisionDetector`
- `pcd.poool.model.physics.config`: `MinimalBoardConf`, `LargeBoardConf`, `MassiveBoardConf`
- `pcd.poool.model.concurrent`: `BoundedBuffer`, `BoundedBufferImpl`
- `pcd.poool.view`: `RenderSynch`
- `pcd.poool.view.board`: `ViewModel`, `View`, `ViewFrame`
- `pcd.poool.controller`: `Cmd`, `ActiveController`
- `pcd.poool.benchmark`: `PhysicsBenchmark`

## Excluded from `pcd.poool`
- sketch02 counter demo artifacts
- sketch bootstrap/demo launchers

They remain under `assignment-1/reference/sketch01` and `assignment-1/reference/sketch02`, outside the Maven source tree.

