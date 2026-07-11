PCD a.y. 2025-2026 - ISI LM UNIBO - Cesena Campus

# Assignment 3 - SHAS

Smart Home Alarm System implemented in Java with Apache Pekko.

## Overview

This project is the initial Apache Pekko Typed setup for assignment 3.

The source tree already contains the package structure that will host the
future alarm-system actors under `pcd.shas`, but the bootstrap entry point is
currently minimal: it creates an actor system and shuts it down immediately.

## Build and Test

From this directory:

```bash
mvn compile
mvn test
```

To run the current bootstrap entry point:

```bash
mvn exec:java -Dexec.mainClass="pcd.shas.Main"
```

## Project Files

- `assignment_3_smart_home_alarm.md`: exercise description
- `src/main/java`: production sources
- `src/test/java`: tests
- `report`: LaTeX source for the report
