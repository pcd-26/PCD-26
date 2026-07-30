# Distributed Critical Sections (DCS) Middleware

This directory contains the implementation of **Exercise #3** of Assignment 4: a high-level middleware providing support for realizing critical sections for processes running in a distributed system, using Java and RabbitMQ.

## Requirements

- Java 21
- Maven 3.9+ or compatible
- RabbitMQ 3.x or compatible with AMQP 0-9-1
- Docker, if you want the helper scripts to start RabbitMQ automatically

## Quick RabbitMQ Start

If you already have Docker installed, you can start a local RabbitMQ broker for this module with:

```bash
docker compose -f docker-compose.rabbitmq.yml up -d
```

The broker exposes:

- AMQP on `localhost:5672`
- the management UI on `http://localhost:15672` (`guest` / `guest`)

To stop it later:

```bash
docker compose -f docker-compose.rabbitmq.yml down
```

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
   - There is no persistent initialization marker, so no bootstrap artifact can outlive the token.

4. **Safe Lock Release**:
   - Inside the `exit()` method, releasing the lock first cancels the local consumer and then requeues the same delivery with `basicNack(..., requeue = true)`.
   - The temporary bootstrap lock is held while this transition happens, so a concurrent bootstrapper cannot observe an intermediate state.

## Queue Topology

For each critical-section name `csName`, the middleware uses two broker queues:

- `cs_token_<csName>`:
  - durable
  - non-exclusive
  - non-auto-delete
  - contains exactly one persistent token message when the critical section is idle
- `cs_bootstrap_lock_<csName>`:
  - non-durable
  - exclusive to the connection that created it
  - auto-deleted by the broker when the connection closes
  - exists only while a process decides whether the token must be seeded

The bootstrap lock queue is not part of the steady-state protocol. It is only a broker-side mutex for initialization and release transitions.

## API and Lifecycle

Typical lifecycle:

1. Construct `DistributedCriticalSection` for a given `csName`.
2. Call `enter()` to block until the token is delivered.
3. Execute the critical section body.
4. Call `exit()` to requeue the token.
5. Call `close()` when the middleware instance is no longer needed.

Important delivery guarantees:

- `enter()` uses manual acknowledgements.
- A successful `enter()` means the caller owns one unacknowledged delivery tag.
- `exit()` requeues that same delivery tag back to the token queue.
- If the process crashes before `exit()`, RabbitMQ requeues the token automatically when the channel closes.
- If the process shuts down cleanly while holding the token, the token is also preserved.

Known failure assumptions:

- RabbitMQ must be reachable when constructing the middleware.
- If RabbitMQ itself loses its queue metadata or messages, the middleware cannot recover state that the broker has already destroyed.
- The protocol assumes a single RabbitMQ broker namespace for the critical section name.

Example usage:

```java
public static void main(String[] args) throws Exception {
    try (DistributedCriticalSection dcs = new DistributedCriticalSection("localhost", 5672, "demo-cs")) {
        dcs.enter();
        try {
            // critical section body
        } finally {
            dcs.exit();
        }
    }
}
```

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
- Concurrent initialization by multiple middleware instances.
- Exactly one token being created.
- Interrupted bootstrap and later recovery.
- Crash while holding the token and RabbitMQ requeue behavior.
- Mutual exclusion with multiple concurrent processes.
- Repeated acquire and release cycles.
- Rejection of release without ownership.
- Rejection of double release.
- Independence of different critical-section names.
- Shutdown cleanup without losing the token.

- **Bash**:
  ```bash
  ./test-dcs.sh
  ```
- **PowerShell**:
  ```powershell
  .\test-dcs.ps1
  ```

### Running Manually

You can also run a single process directly:

```bash
mvn -f assignment-4/dcs/pom.xml exec:java -Dexec.args="Process-A localhost 5672"
```

Or start the demo script:

- **Bash**: `./run-dcs.sh`
- **PowerShell**: `.\run-dcs.ps1`

The scripts attempt to start a temporary RabbitMQ container automatically if no broker is available on port `5672`.

## Limitations

- The middleware is intentionally minimal and models a single critical section as a single token queue.
- It does not include broker clustering, replication, or persistence beyond what RabbitMQ provides for the queue and message durability settings used here.
- If the broker loses the queue or its durable messages, recovery is outside the scope of the middleware.
