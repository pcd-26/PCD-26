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
| **Event-Loop (Vert.x)** | Contained within the Event Loop context. | Since Vert.x executes handlers on the event loop sequentially, standard thread synchronization is not strictly needed. However, `AtomicLong` and `AtomicInteger` are used for safe lock-free cross-context progress tracking. |

### 1.2. Cancellation Strategy

Halting a long-running scan promptly avoids wasting disk and CPU resources:
- **Virtual Threads**: Checks a `volatile boolean` cancellation flag inside the recursive directory walk loop. On cancellation, it terminates the `ExecutorService` thread pool.
- **Reactive**: Cancelling the stream is naturally done by disposing the subscription handle. The internal generator checks `emitter.isDisposed()` at each file and directory boundary, terminating immediately.
- **Event-Loop**: Sets a `volatile boolean` cancellation flag that blocks subsequent asynchronous NIO operations from starting, then calls `vertx.close()` to release the worker threads.

### 1.3. UI Responsiveness & Backpressure

To prevent GUI freezes in Swing, intermediate report updates must not saturate the Event Dispatch Thread (EDT):
- Reports are generated and sampled periodically (every 100ms) rather than on every file scanned.
- All final callbacks on the listeners are forced onto the EDT via `SwingUtilities.invokeLater`.

---

## 2. API Reference

### Common Structures

#### `FSReport` (Record)
An immutable value object containing:
- `directory`: The scanned directory root path.
- `maxFS`: The maximum file size threshold.
- `nb`: The number of file size bands.
- `bandsCount`: Array of size `nb + 1` representing the file distribution.
- `totalFiles`: Total number of files.
- `durationMs`: Total duration of the scan so far.

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
Run the GUI application using the following command:
```bash
mvn -f assignment-2/pom.xml exec:java -Dexec.mainClass="pcd.assignment2.gui.FSStatGUI"
```
