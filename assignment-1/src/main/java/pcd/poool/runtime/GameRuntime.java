package pcd.poool.runtime;

import pcd.poool.model.common.math.V2d;

/** A playable runtime that owns and advances one Poool match. */
public interface GameRuntime extends AutoCloseable {

    /** Starts the runtime. */
    void start();

    /** Queues a human shot. */
    CommandReceiptSupport<Boolean> shootHuman(V2d velocity);

    /** Returns the latest immutable state. */
    RuntimeGameSnapshot snapshot();

    /** Stops the runtime and releases its execution resources. */
    @Override
    void close();
}
