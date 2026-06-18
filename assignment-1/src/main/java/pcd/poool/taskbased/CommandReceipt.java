package pcd.poool.taskbased;

import java.time.Duration;

/**
 * Monitor-based completion receipt for commands submitted to the task-based
 * game runner.
 *
 * @param <T> command result type
 */
public class CommandReceipt<T> {

    private boolean completed;
    private T result;
    private RuntimeException failure;

    CommandReceipt() {
    }

    synchronized void complete(T result) {
        if (completed) {
            return;
        }
        this.result = result;
        completed = true;
        notifyAll();
    }

    synchronized void fail(RuntimeException failure) {
        if (completed) {
            return;
        }
        this.failure = failure;
        completed = true;
        notifyAll();
    }

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
