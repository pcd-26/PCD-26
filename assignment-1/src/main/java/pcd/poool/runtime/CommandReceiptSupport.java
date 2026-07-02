package pcd.poool.runtime;

import java.time.Duration;

/**
 * Shared monitor-backed completion receipt for asynchronously executed
 * commands.
 *
 * @param <T> command result type
 */
public class CommandReceiptSupport<T> {

    private boolean completed;
    private T result;
    private RuntimeException failure;

    protected CommandReceiptSupport() {
    }

    public synchronized void complete(T result) {
        if (completed) {
            return;
        }
        this.result = result;
        completed = true;
        notifyAll();
    }

    public synchronized void fail(RuntimeException failure) {
        if (completed) {
            return;
        }
        this.failure = failure;
        completed = true;
        notifyAll();
    }

    /**
     * Waits until the command has been executed.
     *
     * @param timeout maximum wait duration
     * @return command result
     * @throws InterruptedException if the caller is interrupted while waiting
     * @throws IllegalStateException if the timeout expires before completion
     */
    public synchronized T await(Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!completed) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new IllegalStateException("command did not complete before timeout");
            }
            wait(remaining);
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }
}
