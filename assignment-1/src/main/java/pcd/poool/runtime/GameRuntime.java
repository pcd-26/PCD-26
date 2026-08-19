package pcd.poool.runtime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import pcd.poool.model.common.math.V2d;

/** A playable runtime that owns and advances one Poool match. */
public interface GameRuntime extends AutoCloseable {

    /** Starts the runtime. */
    void start();

    /** Queues a human shot. */
    CompletableFuture<Boolean> shootHuman(V2d velocity);

    /** Returns the latest immutable state. */
    RuntimeGameSnapshot snapshot();

    /** Waits until the published state satisfies a condition. */
    RuntimeGameSnapshot awaitSnapshot(
            Predicate<RuntimeGameSnapshot> condition,
            Duration timeout) throws InterruptedException;

    /** Stops the runtime and releases its execution resources. */
    @Override
    void close();
}
