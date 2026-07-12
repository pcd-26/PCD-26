# Distributed Critical Sections (DCS) Middleware

This directory contains the implementation of **Exercise #3** of Assignment 4: a high-level middleware providing support for realizing critical sections for processes running in a distributed system, using Java and RabbitMQ.

## Design and Concurrency Strategy

The distributed critical section is implemented as a token-based mutual exclusion protocol:

1. **Mutex Token Queue (`cs_token_<csName>`)**:
   - The critical section is represented by a durable RabbitMQ queue containing exactly one persistent "token" message.
   - To acquire the lock, a process consumes the message from this queue (manual acknowledgment mode: `autoAck = false`).
   - Only one process can successfully receive the message at any time, enforcing mutual exclusion.

2. **Fault Tolerance**:
   - If a process crashes or loses connection to RabbitMQ while in the critical section, its AMQP channel/connection is closed.
   - Because the token message was consumed with `autoAck = false`, RabbitMQ automatically detects the closed channel and **requeues** the message.
   - This ensures that if a process holding the lock crashes, the lock is automatically released without causing deadlock.

3. **Crash-Safe Bootstrap**:
   - Bootstrap is serialized with a temporary exclusive broker lock queue `cs_bootstrap_lock_<csName>`.
   - While holding that lock, a process passively inspects `cs_token_<csName>`.
   - The token is published only if the queue has no messages and no registered consumers.
   - This means a process holding the critical section cannot be mistaken for an uninitialized system, and a crash during bootstrap is recoverable by another process.

4. **Safe Lock Release**:
   - Inside the `exit()` method, releasing the lock first cancels the local consumer and then requeues the same delivery with `basicNack(..., requeue = true)`.
   - The temporary bootstrap lock is held while this transition happens, so a concurrent bootstrapper cannot observe an intermediate state.

---

## Configuration and Scripts

Scripts are provided in both Bash (`.sh`) and PowerShell (`.ps1`) formats to run and test the project:

### Running the Demo

The demo runs two concurrent processes (`Process-A` and `Process-B`) that compete for a shared critical section. They log their access to a shared file `dcs_shared.log`.

- **Bash**:
  ```bash
  ./run-dcs.sh
  ```
- **PowerShell**:
  ```powershell
  .\run-dcs.ps1
  ```

### Running the Tests

A comprehensive JUnit 5 test suite is included in `DistributedCriticalSectionTest.java`, covering:
- Basic acquisition and release of locks.
- Mutual exclusion enforcement under concurrent access.
- Non-reentrant behavior (throwing `IllegalStateException` on re-entry).
- Concurrent bootstrap creating exactly one token.
- Connection crash recovery (automatic requeueing of tokens).

- **Bash**:
  ```bash
  ./test-dcs.sh
  ```
- **PowerShell**:
  ```powershell
  .\test-dcs.ps1
  ```

*Note: The run and test scripts will automatically attempt to spin up a temporary RabbitMQ container using Docker if no local RabbitMQ instance is detected on port 5672.*
