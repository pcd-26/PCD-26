# FSStatLib - Concurrency Design and Implementation

This document details the architectural choices, shared state ownership, synchronization strategies, and execution instructions for the `FSStatLib` library.

---

## 1. Concurrency Aspects & Design Choices

The directory scanner is an I/O-bound operation. Reading large directory trees concurrently presents challenges regarding thread scheduling, shared state management, and user interface responsiveness.

### 1.1. Shared State & Synchronization Strategies

Each paradigm handles shared state differently to avoid data races and minimize thread contention:

| Paradigm | Shared State Ownership | Synchronization Strategy |
| :--- | :--- | :--- |
| **Virtual Threads** | Thread-safe shared accumulators (`LongAdder` array & `LongAdder` total count). | Lock-free thread-safe atomic additions (extremely low CPU cache contention). Coordinate task termination using `AtomicInteger` tracker and `CountDownLatch`. |
| **Reactive (RxJava)** | None (Functional Immutable state). | State is held within an immutable `Accumulator` object that is passed along the stream using the `.scan()` operator. No shared mutable state exists. |
| **Event-Loop (Vert.x)** | Event-loop callbacks own the traversal flow, while blocking filesystem checks are offloaded to Vert.x worker threads. | Directory reads and file property queries stay async (`readDir`, `props`). Path validation and canonicalization are wrapped in `executeBlocking` so the event loop does not block. `AtomicLong` and `AtomicInteger` are still used for safe lock-free cross-context progress tracking. |

### 1.2. Cancellation Strategy

Halting a long-running scan promptly avoids wasting disk and CPU resources:
- **Virtual Threads**: Checks a `volatile boolean` cancellation flag inside the recursive directory walk loop. On cancellation, it terminates the `ExecutorService` thread pool.
- **Reactive**: Cancelling the stream is naturally done by disposing the subscription handle. The internal generator checks `emitter.isDisposed()` at each file and directory boundary, terminating immediately.
- **Event-Loop**: Sets a `volatile boolean` cancellation flag that blocks subsequent asynchronous NIO operations from starting, then closes Vert.x only after in-flight callbacks have drained. Blocking path validation still happens, but it runs in the worker pool rather than on the event loop.

### 1.3. UI Responsiveness & Backpressure

To prevent GUI freezes in Swing, intermediate report updates must not saturate the Event Dispatch Thread (EDT):
- Reports are generated and sampled periodically (every 100ms) rather than on every file scanned.
- All final callbacks on the listeners are forced onto the EDT via `SwingUtilities.invokeLater`.

### 1.4. Size Units and Time Formatting

- The internal report model stores file sizes in **bytes** and timings in **milliseconds**.
- The CLI and GUI expose a selectable display/input unit (`B`, `KiB`, `MiB`, `GiB`) and convert it to bytes before scanning. For convenience, the parser also accepts `KB`, `MB`, and `GB` as aliases.
- User-facing duration output is normalized to `seconds (ms)` format, for example `1.234 s (1234 ms)`.

---

## 2. API Reference

### Common Structures

#### `FSReport` (Record)
An immutable value object containing:
- `directory`: The scanned directory root path.
- `maxFS`: The maximum file size threshold, stored internally in bytes.
- `nb`: The number of file size bands.
- `bandsCount`: Array of size `nb + 1` representing the file distribution.
- `totalFiles`: Total number of files.
- `durationMs`: Total duration of the scan so far, in milliseconds.

#### `FSReportListener` (Interface)
Defines callbacks for async updates:
- `void onUpdate(FSReport report)`
- `void onCompleted(FSReport report)`
- `void onError(Throwable error)`

#### `FSReportJob` (Interface)
Allows control over a running scan:
- `void cancel()`
- `boolean isCancelled()`

---

## 3. How to Run the GUI Demonstration

You can launch the Swing-based interactive GUI to configure parameters, select programming paradigms, and start/stop directory scans.

### Compilation
Compile the project using Maven:
```bash
mvn -f assignment-2/pom.xml clean compile
```

### Execution
Run the GUI application using one of the helper scripts, which now always perform a clean build before launching:
```bash
./assignment-2/run-gui.sh
```
```powershell
.\assignment-2\run-gui.ps1
```

If you prefer to call Maven directly, use:
```bash
mvn -f assignment-2/pom.xml clean compile exec:java -Dexec.mainClass="pcd.assignment2.gui.FSStatGUI"
```

## 4. CLI Examples

The CLI accepts an optional size unit and an optional paradigm:

```bash
./assignment-2/run-cli.sh . 10 5 MB vt
./assignment-2/run-cli.sh . 10485760 5 vt
./assignment-2/run-cli.sh . 50 4 KB rx
./assignment-2/run-cli.sh . 10 5 MB loop
```

If the unit is omitted, bytes are assumed. If the paradigm is omitted, `vt` is used.

The helper scripts also perform a clean build before launching:

```bash
./assignment-2/run-cli.sh . 10 5 MB vt
```
```powershell
.\assignment-2\run-cli.ps1 . 10 5 MB vt
```
