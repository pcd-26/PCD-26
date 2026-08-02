# FSStatLib Internal Project Documentation

This document is intended for maintainers of `assignment-2`. It describes the
code layout, the execution flow, the responsibilities of each package, and the
main implementation tradeoffs used in the three filesystem scanners.

---

## 1. Project Overview

`assignment-2` implements `FSStatLib`, a filesystem statistics library that
scans a directory recursively and produces a report containing:

- the total number of regular files discovered;
- the distribution of file sizes across `nb + 1` bands;
- the elapsed scan time.

The project exposes three asynchronous implementations of the same logical
behaviour:

- `virtualthreads`
- `reactive`
- `eventloop`

The CLI and GUI can select any of the three implementations at runtime.

---

## 2. Source Layout

| Path | Responsibility |
| :--- | :--- |
| `src/main/java/pcd/fsstat/common` | Shared data types and utilities. |
| `src/main/java/pcd/fsstat/virtualthreads` | Virtual-thread implementation. |
| `src/main/java/pcd/fsstat/reactive` | RxJava implementation. |
| `src/main/java/pcd/fsstat/eventloop` | Vert.x event-loop implementation. |
| `src/main/java/pcd/fsstat/cli` | Console entry point. |
| `src/main/java/pcd/fsstat/gui` | Swing demo application. |
| `src/test/java/pcd/fsstat` | Unit, integration-style, and robustness tests. |

The `docs/` directory contains maintainers-only documentation:

- `DESIGN.md` for the architectural rationale;
- `VALIDATION.md` for the test and robustness strategy;
- this file for a compact codebase map and maintenance guide.

---

## 3. Shared Model

### `FSReport`

`FSReport` is the immutable report model returned by every implementation.
It stores:

- the scanned directory path;
- the `maxFS` threshold in bytes;
- the number of bands `nb`;
- the file counts per band;
- the total number of scanned files;
- the elapsed time in milliseconds.

The record defensively copies the `bandsCount` array on construction and on
readback so callers cannot mutate internal state.

### `FSReportListener`

`FSReportListener` is the callback contract used by the imperative
implementations:

- `onUpdate(FSReport)` for periodic progress snapshots;
- `onCompleted(FSReport)` for the final result;
- `onError(Throwable)` for terminal failures.

### `FSReportJob`

`FSReportJob` is the cancellation handle returned by the imperative
implementations. It currently exposes:

- `cancel()`;
- `isCancelled()`.

### `SizeUnit`

`SizeUnit` centralizes the user-facing unit conversion logic.
The internal model always stays in bytes; the enum is only used by the CLI and
GUI for parsing and formatting.

### `FSUtils`

`FSUtils` holds small helpers that are shared across the three engines:

- conversion from mutable counters to primitive arrays;
- report construction with elapsed time;
- `AtomicLong` array initialization for the event-loop implementation.

---

## 4. Implementation Notes

### 4.1 Virtual Threads

`VirtualThreadsFSStat` uses a `newVirtualThreadPerTaskExecutor()` and a
recursive directory walk.

Key points:

- a `LongAdder` per band and one `LongAdder` for the total file count;
- a `CountDownLatch` plus `AtomicInteger` to detect completion;
- a background reporter thread that emits periodic progress updates;
- a `ConcurrentHashMap.newKeySet()` to avoid visiting the same canonical
  directory twice.

Cancellation:

- the job sets a volatile flag;
- the reporter thread is interrupted;
- the executor is shut down with `shutdownNow()`.

Notes for maintainers:

- this implementation favors straightforward imperative code;
- task spawning is intentionally local to the recursive walk;
- if you touch completion logic, keep the `runningTasks` bookkeeping aligned
  with every `submit()` path.

### 4.2 Reactive

`ReactiveFSStat` uses RxJava to model the scan as a stream of files.

Key points:

- directory traversal is produced by `scanFiles(rootDir)`;
- `Observable.scan()` accumulates immutable scan state;
- `sample(100, TimeUnit.MILLISECONDS, true)` throttles UI-facing updates;
- the state object is recreated on every file emission.

Notes for maintainers:

- the stream is intentionally functional and does not share mutable counters;
- `scanFiles()` performs the recursive directory walk and stops when the
  subscriber is disposed;
- if you change the state structure, make sure the accumulator copy logic in
  `ScanState(ScanState previous, File file)` stays cheap and correct.

### 4.3 Event Loop

`EventLoopFSStat` uses Vert.x callbacks and offloads blocking path validation
with `executeBlocking`.

Key points:

- `Vertx.vertx()` is created per scan;
- a periodic timer emits progress updates;
- directory traversal is driven by asynchronous `readDir()` and `props()`
  calls;
- `AtomicInteger` tracks outstanding work items;
- `ConcurrentHashMap.newKeySet()` stores canonical directories already seen.

Notes for maintainers:

- cancellation must stop both the timer and the Vert.x instance;
- the job closes the Vert.x instance only once, guarded by `closed`;
- the `outstandingTasks` counter must stay in sync with every async branch,
  otherwise completion can fire too early or never fire.

---

## 5. Application Entry Points

### CLI

`FSStatCLI` is the console entry point.

Responsibilities:

- parse the command-line arguments;
- validate the input directory;
- convert the selected size unit into bytes;
- dispatch to the selected implementation;
- block until the scan completes or fails.

Supported paradigms:

- `vt` for virtual threads;
- `rx` for RxJava;
- `loop` for Vert.x.

The CLI keeps a `CountDownLatch` so the process remains alive until the scan
has produced a final result.

### GUI

`FSStatGUI` is the Swing demo application.

Responsibilities:

- let the user choose directory, threshold, band count, and paradigm;
- start and cancel scans;
- display periodic updates without blocking the EDT;
- show the final report in a table.

The GUI normalizes callback delivery by wrapping imperative listeners in
`SwingUtilities.invokeLater(...)`.

For RxJava, the GUI stores the `Disposable` returned by the subscription and
disposes it when the user presses Cancel.

---

## 6. Test Coverage Map

The test suite is split by scope:

- `FSStatTest`
  - unit coverage for `FSReport`, `SizeUnit`, and the three engines on a small
    fixture;
  - cancellation behaviour for the event-loop engine;
  - CLI helper parsing and subscription glue.
- `FSStatRobustnessCorrectnessTest`
  - empty directories;
  - boundary sizes;
  - nested trees;
  - invalid paths;
  - unreadable directories;
  - symlink cycles;
  - large sparse files;
  - concurrent mutations;
  - special files.
- `FSStatCLITest`
  - command-line parsing and subscription helpers.
- `FSStatBenchmark`
  - local performance experiments.

If you change the scan semantics, the robustness suite is the first place to
update.

---

## 7. Build And Run

Compile and test:

```bash
mvn -f assignment-2/pom.xml clean test
```

Run the CLI:

```bash
./assignment-2/run-cli.sh . 10 5 MiB vt
```

Run the GUI:

```bash
./assignment-2/run-gui.sh
```

The report package for delivery is generated from `assignment-2/report` and is
kept separate from the source tree that gets delivered in the final zip.

---

## 8. Maintenance Guidelines

- Keep the three implementations behaviourally aligned unless a discrepancy is
  intentional and documented.
- Prefer changes in `common/` only when logic is truly shared.
- Keep UI callbacks on the EDT.
- When touching cancellation, verify that the final callback still happens
  exactly once, or not at all after a successful cancel.
- Update `DESIGN.md` and `VALIDATION.md` if the implementation strategy or the
  test coverage changes.
