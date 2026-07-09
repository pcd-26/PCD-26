# FSStatLib Validation and Robustness Report

This document records the validation methodology, controlled test datasets, test execution results, consistency checks, and known limitations of the three filesystem scanning implementations: Virtual Threads, RxJava (Reactive), and Vert.x (Event-Loop).

---

## 1. Controlled Test Datasets & Edge Cases

To systematically validate correctness, correctness consistency, and error resilience, we implemented the following test scenarios under the JUnit suite `FSStatRobustnessCorrectnessTest`:

1. **Empty Directory (`testEmptyDirectoryConsistency`)**:
   - **Setup**: Creates an empty temporary directory.
   - **Goal**: Confirm that all implementations report `0` total files and all size bands containing `0`.
2. **Flat Directory with Boundary Sizes (`testFlatDirectoryConsistency`)**:
   - **Setup**: Creates files of size exactly matching limits: `0`, `10`, `25`, `50`, `75`, `100`, `101`, and `250` bytes. The scanner uses `maxFS = 100` and `nb = 4` (width = 25).
   - **Goal**: Verify math and consistency for boundary values.
3. **Deeply Nested Directory Hierarchy (`testNestedDirectoryConsistency`)**:
   - **Setup**: Recursively nested directories up to 4 levels deep containing various sized files.
   - **Goal**: Verify stable directory traversal recursion.
4. **Invalid Directory Path (`testInvalidDirectoryPath`)**:
   - **Setup**: Runs the scanner on a non-existent path.
   - **Goal**: Ensure the error is caught and propagated via `onError` without throwing unhandled exceptions or crashing.
5. **Regular File Path (`testRegularFilePath`)**:
   - **Setup**: Runs the scanner with a path pointing to a regular file instead of a directory.
   - **Goal**: Ensure the error is caught and handled via `onError` gracefully.
6. **Restricted Directory Permissions (`testRestrictedPermissions`)**:
   - **Setup**: Creates a subdirectory with files, then revokes its read permissions (`setReadable(false)`).
   - **Goal**: Confirm that the scanner continues scanning readable files and gracefully skips restricted subdirectories instead of crashing or hanging.
7. **Circular Symbolic Links (`testCircularSymbolicLinks`)**:
   - **Setup**: Creates a directory cycle on Unix systems using directory symlinks (`dirA/linkToA -> dirA`).
   - **Goal**: Confirm that the scanners detect cycles and skip circular directory trees, preventing infinite traversal loop hangs or Stack Overflow errors.
8. **Large File Sizes (`testLargeFileSizes`)**:
   - **Setup**: Creates a sparse file of size 3 GB using `RandomAccessFile.setLength(...)`.
   - **Goal**: Verify that sizes exceeding 32-bit integer limits (`Integer.MAX_VALUE` bytes) are handled without numerical overflow and categorized in the correct bands (e.g., band 4 for files > 1 GB).
9. **Concurrent File Mutation (`testConcurrentFileMutation`)**:
   - **Setup**: Launches a background thread to delete a subset of files during a running scan.
   - **Goal**: Ensure the scan finishes cleanly without crashing, catching any dynamic file-deletion/access errors.
10. **Special Files (`testSpecialFiles`)**:
    - **Setup**: Creates a symbolic link to `/dev/null` (character special device).
    - **Goal**: Verify that the scanners skip non-regular files (devices, sockets, pipes) and do not block or count them.

---

## 2. Test Execution & Outputs Consistency

All tests compile and pass under Maven. Here is the execution summary:

- **Total Tests Run**: 19 (including happy-path unit tests and the robustness/correctness suite)
- **Status**: 19 Passed, 0 Failed, 0 Skipped
- **Consistency Verification**: In all test scenarios, the resulting `FSReport` (total file count, size distribution bands, structure) returned by Virtual Threads, RxJava, and Event-Loop are **perfectly identical**.

---

## 3. Comparison of Implementations & Known Limitations

Each implementation has different runtime behaviors, scalability constraints, and edge-case limitations:

### 3.1. Virtual Threads (vt)

- **Pros**:
  - Direct, readable, imperatively written code.
  - Highly parallelized: Spawns sub-directory walks as separate tasks.
- **Limitations**:
  - **Thread/Memory Overhead**: Spawning a virtual thread per subdirectory works well for moderate trees, but on massive directory trees (millions of folders), allocating millions of virtual threads could incur JVM memory pressure.
  - **Carrier Thread Pinning**: Under certain conditions where native OS libraries or legacy JVM file access operations block, the underlying carrier thread can be pinned, limiting concurrency.

### 3.2. Reactive Programming (rx)

- **Pros**:
  - Functional design: Zero shared mutable state.
  - Built-in sampling and backpressure helpers.
- **Limitations**:
  - **Single-Threaded Directory Walker**: Unlike the virtual-thread implementation which walks subdirectories in parallel, the RxJava walk loop sequentially emits files on `Schedulers.io()`. This makes it slower for large directory trees.
  - **Stack Overflow Risk**: The sequential traversal uses stack recursion (`walkRecursive`). Extremely deep directory nesting (thousands of levels) will trigger a JVM `StackOverflowError`.

### 3.3. Event-Loop / Vert.x (loop)

- **Pros**:
  - Immunized from call-stack overflows since recursion is deferred asynchronously onto the event loop.
  - Extremely light on thread footprint.
- **Limitations**:
  - **NIO Context Overhead**: Every file property read (`fs.props`) and directory read (`fs.readDir`) is queued as a separate task on the event loop. For trees containing many tiny files, the scheduler overhead and task queues create significant memory pressure and can degrade performance.
  - **File Descriptor Limits**: Massive parallel non-blocking read/stat calls could exhaust available OS file descriptors if the OS limit is low and many directories are queued.
  - **Asynchronous Loop Cancellation**: Cancelling requires closing the entire Vertx context. Lingering NIO operations already scheduled on worker threads might complete and fire callbacks before the context shuts down, requiring safe state checks.

---

## 4. Stability Recommendations

- **Circular/Looping Folders**: Standard canonical path checks are implemented in all three models, meaning they are fully protected against symlink cycles.
- **Resource Constraints**: For scanning large directories (e.g., system drives), `vt` (Virtual Threads) is the most efficient and recommended paradigm due to parallel multi-core exploitation. Avoid `loop` (Event-Loop) for filesystems with huge quantities of small files due to NIO event-loop queue overhead.
