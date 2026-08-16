PCD a.y. 2025-2026 - ISI LM UNIBO - Cesena Campus

# Assignment 3 - SHAS

Smart Home Alarm System implemented in Java with Apache Pekko.

## Overview

This project is the initial Apache Pekko Typed setup for assignment 3.

The source tree now defines the smart-home alarm domain model plus typed
peripheral actors under `pcd.shas`: the control unit, keypad, sensor, and
siren. The keypad forwards PIN submissions and arming-mode requests, while the
sensor actor only forwards intrusion events. The control unit owns the state
machine, the active-zone set, and the configurable timers.
The default entry point starts a small CLI simulator. A separate demo entry
point runs the complete scripted flow and a partial-arming night mode before
shutting the system down cleanly.

## Configuration

The application reads its defaults from `src/main/resources/application.conf`.
The current keys are:

- `shas.correctPin`
- `shas.exitDelay`
- `shas.entryDelay`

Tests can override these values with in-memory Typesafe Config instances
without changing the production file.

`Main` uses these values as-is. `DemoMain` keeps the same PIN but uses short
in-memory delays so the scripted scenario completes quickly.

## Zone Control

Sensors are associated with exactly one logical zone. The control unit accepts
full arming or partial arming with an immutable set of active zones. When the
system returns to `DISARMED`, the active-zone selection is reset to the full
set.

## Build and Test

From this directory:

```bash
mvn compile
mvn test
```

If your environment cannot reach Maven Central directly, the repository ships
with a Maven settings mirror used by the CI workflow and the helper scripts.
From this module, you can invoke Maven with:

```bash
mvn -s ../../.mvn/settings.xml test
```

To run the interactive CLI:

```bash
mvn exec:java
```

In the CLI, arming is requested with `arm full <PIN>` or
`arm partial <PIN> ...`. When the system is already armed, in entry delay, or
in alarm, `pin <PIN>` disarms the system or silences the siren.
Sensor events can be simulated with `front door`, `ground floor`,
`living room`, and `bedroom`, covering all configured zones.

To run the scripted demo:

```bash
mvn exec:java -Dexec.mainClass=pcd.shas.DemoMain
```

## Project Files

- `assignment_3_smart_home_alarm.md`: exercise description
- `src/main/java`: production sources
- `src/test/java`: tests
- `report`: LaTeX source for the report
