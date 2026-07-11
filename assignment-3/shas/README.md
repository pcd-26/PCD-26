PCD a.y. 2025-2026 - ISI LM UNIBO - Cesena Campus

# Assignment 3 - SHAS

Smart Home Alarm System implemented in Java with Apache Pekko.

## Overview

This project contains the first exercise of assignment 3: a concurrent alarm
system based on typed actors.

The system is organized around these actors under `pcd.shas`:

1. `ControlUnitActor`: central FSM controller with arming, delays, and alarm handling
2. `KeypadActor`: collects keypad input and zone selections
3. `SensorActor`: simulates physical sensors in configured zones
4. `SirenActor`: simulates the siren device

## Build and Test

From this directory:

```bash
mvn compile
mvn test
```

To run the CLI simulator:

```bash
mvn exec:java -Dexec.mainClass="pcd.shas.Main"
```

## Project Files

- `assignment_3_smart_home_alarm.md`: exercise description
- `src/main/java`: production sources
- `src/test/java`: tests
- `report`: LaTeX source for the report

