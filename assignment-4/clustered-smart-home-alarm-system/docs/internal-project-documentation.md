# Clustered Smart Home Alarm System Internal Project Notebook

This document is the internal map for the clustered smart home alarm system.
It collects the facts that are useful while we simplify code, keep the runtime
stable, and prepare the final report.

It is not a public README. It is the working note we should consult before
changing code, startup commands, or report content.

## 1. How to use this notebook

When starting a task:

1. read the relevant section below;
2. jump to the linked source or doc file;
3. check the matching tests;
4. update this notebook if the architecture or report story changes.

## 2. Assignment brief, in our own words

This sub-project extends the smart-home alarm system from the previous
assignment so that it runs on an Apache Pekko Cluster.

The important points are:

- the alarm logic must keep the same states and timing behavior;
- the system must run on multiple cluster nodes;
- sensors, keypads, and the control unit may live on different nodes;
- communication must happen only through actor messages;
- if the control unit is recreated, it must restart in a safe recovery mode.

## 3. What the brief asks for, and where it is covered

| Requirement from the brief | Where it is covered | Status |
| --- | --- | --- |
| same alarm-state rules as before | `pcd.shas.controlunit`, `pcd.shas.common` | implemented |
| at least three cluster nodes | `pcd.shas.runtime.NodeStartup`, `README.md` | implemented |
| distributed sensors and keypads | `pcd.shas.sensor`, `pcd.shas.keypad` | implemented |
| communication only through actor messages | typed actor protocols in `pcd.shas.*` | implemented |
| same timing behavior as the previous assignment | control-unit timers and tests | implemented |
| safe recovery after restart | control-unit recovery state and tests | implemented |

## 4. Source of truth map

### 4.1 Public-facing entry points

- [`README.md`](../README.md)
- [`run-cshas.sh`](../run-cshas.sh)
- [`run-cshas.ps1`](../run-cshas.ps1)
- [`test-cshas.sh`](../test-cshas.sh)
- [`test-cshas.ps1`](../test-cshas.ps1)

### 4.2 Code

- `src/main/java/pcd/shas/common`
- `src/main/java/pcd/shas/controlunit`
- `src/main/java/pcd/shas/keypad`
- `src/main/java/pcd/shas/runtime`
- `src/main/java/pcd/shas/sensor`
- `src/main/java/pcd/shas/siren`

### 4.3 Tests

- `src/test/java/pcd/shas`

### 4.4 Report source

- `report/`

## 5. Architecture story

### 5.1 Control unit

The control unit owns the alarm state machine and is the only place where the
system status changes.

What to keep in mind:

- it starts in a safe recovery state after recreation;
- it handles PIN submissions and sensor activations through typed messages;
- it uses timers for arming and entry delays;
- it ignores or logs sensor events until a correct PIN restores a valid mode.

### 5.2 Keypad

The keypad is local to a node and only forwards user input.

What to keep in mind:

- it should not own alarm state;
- it should not call control-unit internals directly;
- it should remain a thin actor that translates local input into messages.

### 5.3 Sensors

Sensors are distributed actors with a stable `sensorId`.

What to keep in mind:

- the system supports motion sensors and door/window sensors;
- sensor events must be serializable and safe to send across nodes;
- the sensor is only a source of events, not a state owner.

### 5.4 Runtime bootstrap

`NodeStartup` is the entry point for cluster configuration.

What to keep in mind:

- it validates the system identity and node role;
- it resolves seed nodes and ports;
- it builds the cluster configuration text;
- it must stay simple enough to explain in the report.

## 6. Tests that matter most

The tests should protect the things that are easy to break during refactoring:

- control-unit state transitions;
- timer behavior and delayed alarms;
- recovery after restart;
- serialization of remote messages;
- clustered startup and node parsing.

If a simplification changes any of these behaviors, it needs a matching test.

## 7. Report-ready concept list

If we want a short conceptual summary for the report, it is this one:

- distributed actors with message-only communication;
- explicit cluster topology;
- state ownership in the control unit;
- safe recovery after restart;
- stable sensor identity;
- timer-driven alarm behavior.

