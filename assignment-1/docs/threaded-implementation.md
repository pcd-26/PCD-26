# Platform-thread implementation

The platform-thread version has two explicit thread levels.

## Runtime controller

`ThreadedGameRunner` starts one `poool-threaded-controller` thread. It repeatedly
invokes `GameLoop.tick`, sleeps for the configured fixed interval, and publishes
the final snapshot on shutdown. An optional `poool-threaded-bot` thread runs
`BotAgent` and only submits mailbox commands.

## Physics workers

`ThreadedPhysicsEngine` is a small facade. It constructs
`ParallelPhysicsEngine` with `PlatformThreadRangeScheduler`. The scheduler owns
long-lived `PhysicsWorker` platform threads. For each parallel phase it:

1. partitions a contiguous index range;
2. assigns one chunk to each used worker;
3. waits on `WorkerCompletionMonitor`;
4. returns control to the shared physics kernel for merge/commit.

Workers are reused across ticks and closed only after the controller stops.
There is no Executor Framework in this implementation.

## Monitor behavior

`WorkerCompletionMonitor` counts unfinished chunks, stores the first worker
failure, and wakes the coordinator when every assigned chunk reports. The
barrier is required before shared merge and is therefore a correctness boundary
as well as a coordination cost measured by the benchmarks.

For the complete cross-version flow, see
[`runtime-architecture.md`](runtime-architecture.md).
