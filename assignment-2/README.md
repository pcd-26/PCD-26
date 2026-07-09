PCD a.y. 2025-2026 - ISI LM UNIBO - Cesena Campus

# Assignment #02 - `FSStat`

v1.0.0-20260412

This assignment implements `FSStatLib`, an asynchronous library that scans a directory tree and computes:

- the total number of files, recursively;
- the file-size distribution across `NB + 1` bands;
- the number of files larger than `MaxFS`.

Three independent implementations are provided:

1. Event-loop based asynchronous programming
2. Reactive programming with RxJava
3. Virtual Threads

An interactive Swing GUI is also included to start and cancel scans and to view progress updates live.

## Requirements Recap

The `getFSReport` operation scans a directory `D` and produces a report containing:

- the total number of files under `D`, including subdirectories;
- the distribution of file sizes over `NB` bands in the range `[0, MaxFS]`;
- one extra band for files with size greater than `MaxFS`.

Internally, sizes are handled in **bytes**. For user-facing input, the GUI and CLI can display or accept a selectable size unit (`B`, `KiB`, `MiB`, `GiB`) and convert it to bytes automatically. Common aliases like `KB/MB/GB` are also accepted as input for convenience, but the displayed labels use binary prefixes.

Execution time is shown as:

- seconds
- followed by milliseconds in parentheses

Example: `1.234 s (1234 ms)`

## Project Layout

- `src/main/java/pcd/assignment2/common`: shared report and utility classes
- `src/main/java/pcd/assignment2/virtualthreads`: virtual-thread implementation
- `src/main/java/pcd/assignment2/reactive`: RxJava implementation
- `src/main/java/pcd/assignment2/eventloop`: Vert.x implementation
- `src/main/java/pcd/assignment2/cli`: command-line entry point
- `src/main/java/pcd/assignment2/gui`: Swing GUI
- `src/test/java/pcd/assignment2`: unit tests and benchmark helper

## Prerequisites

- JDK 21
- Maven 3.9+ or a compatible Maven installation

## How to Build

From the repository root:

```bash
mvn -f assignment-2/pom.xml compile
```

If you want to run the full verification suite:

```bash
mvn -f assignment-2/pom.xml test
```

## How to Run

All commands below are meant to be run from the repository root.

> [!NOTE]
> The helper shell scripts (`.sh` and `.ps1` files) launch the application directly. They do not run the Maven test suite first. If you want to run the tests manually, use the dedicated Maven command below.

### 1. GUI mode

Launch the interactive Swing application:

```bash
./assignment-2/run-gui.sh
```

On Windows PowerShell:

```powershell
.\assignment-2\run-gui.ps1
```

If script execution is blocked by PowerShell policy, use:

```powershell
powershell -ExecutionPolicy Bypass -File .\assignment-2\run-gui.ps1
```

Or directly with Maven:

```bash
mvn -f assignment-2/pom.xml compile exec:java -Dexec.mainClass="pcd.assignment2.gui.FSStatGUI"
```

### 2. CLI mode

Launch the console version:

```bash
./assignment-2/run-cli.sh <directory> <maxFS> <nb> [sizeUnit: B|KiB|MiB|GiB] [paradigm: vt|rx|loop]
```

On Windows PowerShell:

```powershell
.\assignment-2\run-cli.ps1 <directory> <maxFS> <nb> [sizeUnit: B|KiB|MiB|GiB] [paradigm: vt|rx|loop]
```

If script execution is blocked by PowerShell policy, use:

```powershell
powershell -ExecutionPolicy Bypass -File .\assignment-2\run-cli.ps1 <directory> <maxFS> <nb> [sizeUnit: B|KiB|MiB|GiB] [paradigm: vt|rx|loop]
```

Examples:

```bash
./assignment-2/run-cli.sh . 10 5 MiB vt
./assignment-2/run-cli.sh . 10485760 5 vt
./assignment-2/run-cli.sh . 50 4 KiB rx
./assignment-2/run-cli.sh . 10 5 MiB loop
```

Direct Maven equivalent:

```bash
mvn -f assignment-2/pom.xml compile exec:java -Dexec.mainClass="pcd.assignment2.cli.FSStatCLI" -Dexec.args="<directory> <maxFS> <nb> [sizeUnit: B|KiB|MiB|GiB] [paradigm: vt|rx|loop]"
```

Notes:

- `vt` uses virtual threads
- `rx` uses RxJava
- `loop` uses Vert.x
- if no size unit is provided, the CLI assumes bytes
- if no paradigm is provided, the CLI defaults to `vt`

## GUI Usage

The GUI lets you:

- choose the directory to scan
- choose the maximum file size and its unit
- choose the number of bands
- choose the execution paradigm
- start and cancel the scan
- observe file counts and elapsed time updates live

The result table shows band labels in the selected unit and the execution time in the `seconds (ms)` format.

## Notes on Timing and Units

- file sizes are always computed from `File.length()`, so the internal unit is bytes
- the selected display/input unit only affects the user interface and CLI parsing
- the report itself stores sizes in bytes to keep the implementation consistent across all three paradigms

## Tests

The test suite includes:

- band-index verification
- happy-path scans for all three implementations
- empty-directory coverage for the reactive implementation
- CLI Rx subscription regression coverage
- duration and size-unit formatting tests
