# Clustered Smart Home Alarm System

This module implements the smart home alarm system from the previous assignment
as a small Apache Pekko Typed cluster.

## Architecture

The runtime is split into three roles:

- `control-unit`: owns the alarm state machine and siren control;
- `keypad`: collects local input and forwards PIN or arming requests;
- `sensor`: represents one distributed sensor and forwards activations.

Actors communicate only through immutable typed messages. Remote messages use
the `pcd.shas.common.MySerializable` marker and are serialized with Jackson
JSON.

## Package Organization

- `pcd.shas`: node entry point, distributed demo entry point, and application configuration loader;
- `pcd.shas.common`: shared value objects, enums, and serialization marker;
- `pcd.shas.controlunit`: clustered control-unit actor and state machine;
- `pcd.shas.keypad`: keypad actor and local PIN input protocol;
- `pcd.shas.sensor`: sensor actor and activation protocol;
- `pcd.shas.siren`: siren actor discovered through the receptionist;
- `pcd.shas.runtime`: startup parsing and Pekko Cluster configuration.

## Actor Responsibilities

- Control unit: starts in `STARTUP_RECOVERY`, accepts PIN submissions and sensor
  activations, manages exit-delay and entry-delay timers, and updates sirens.
- Keypad: stores local keystrokes, builds PINs, and forwards PIN, full-arming,
  and partial-arming requests to the discovered control unit.
- Sensor: owns a stable `sensorId`, a `SensorType`, and a `Zone`; forwards
  activations to the control unit as `SensorInfo`.
- Siren: accepts activate/deactivate commands and exposes its state for tests.

The control unit keeps the same timer-driven state machine as the previous
2. keypad node
3. sensor node

Discovery uses the Pekko receptionist, so the keypad and sensor do not need
hard-coded `ActorRef`s for the control unit. The process demo starts the same
three roles as separate localhost processes using ports `2551`, `2552`, and
`2553`.

`Main` is the interactive entry point for one cluster node. `DemoMain` is the
distributed demo entry point: it starts separate `Main` processes, sends
commands to their CLIs, and prints prefixed node output so the message flow can
be inspected from one terminal.

## Configuration Files

- `src/main/resources/application.conf`: Pekko Cluster, serialization bindings,
  and alarm timings loaded by `AlarmConfiguration`.
- `src/main/resources/logback.xml`: console logging configuration for the
  application and tests.

Important configuration entries:

- `shas.correctPin`: PIN required to leave `STARTUP_RECOVERY`, arm, or disarm the system;
- `shas.exitDelay`: delay between arming and the `ARMED` state;
- `shas.entryDelay`: delay between intrusion detection and the `ALARM` state;
- `pekko.actor.provider`: set to `cluster`;
- `pekko.cluster.seed-nodes`: seed nodes used for cluster formation;
- `pekko.remote.artery.canonical.hostname` and `.port`: node identity for each
  JVM process;
- `pekko.actor.serialization-bindings`: enables JSON serialization for remote
  messages.

## Startup Instructions

### Control unit node

```bash
./run-cshas.sh control-unit --host 127.0.0.1 --port 2551 --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

PowerShell:

```powershell
.\run-cshas.ps1 control-unit --host 127.0.0.1 --port 2551 --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

### Keypad node

```bash
./run-cshas.sh keypad --host 127.0.0.1 --port 2552 --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

PowerShell:

```powershell
.\run-cshas.ps1 keypad --host 127.0.0.1 --port 2552 --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

### Sensor node

```bash
./run-cshas.sh sensor --host 127.0.0.1 --port 2553 --sensor-id front_door --sensor-type DOOR_WINDOW --zone PERIMETER --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

PowerShell:

```powershell
.\run-cshas.ps1 sensor --host 127.0.0.1 --port 2553 --sensor-id front_door --sensor-type DOOR_WINDOW --zone PERIMETER --seed-nodes 127.0.0.1:2551,127.0.0.1:2552,127.0.0.1:2553
```

### Distributed three-process demo

```bash
./run-cshas.sh demo
```

PowerShell:

```powershell
.\run-cshas.ps1 demo
```

## Testing

Run the full project verification from this module:

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Useful focused checks:

- `mvn --batch-mode --no-transfer-progress test`
- `mvn --batch-mode --no-transfer-progress -Dtest=pcd.shas.ClusteredSystemTest test`

The test suite covers:

- the control-unit state machine;
- keypad and sensor message delivery;
- clustered discovery and remote message serialization;
- recreation of the control unit in `STARTUP_RECOVERY`.

## Assumptions and Limitations

- The distributed setup assumes three local ports by default: `2551`, `2552`,
  and `2553`.
- The process demo is intended for quick inspection and uses fixed localhost
  ports.
- The control unit is intentionally stateless across recreation; no persistence
  layer is used.
- Cluster discovery depends on the receptionist and seed nodes being available
  during startup.
