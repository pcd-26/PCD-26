package pcd.poool.runtime;

import java.time.Duration;

// Monitor-backed completion receipt for asynchronously executed commands.
public class CommandReceiptSupport<T> {

    private boolean completed;
    private T result;
    private RuntimeException failure;

    public CommandReceiptSupport() {
    }

    // Marks the command as completed successfully.
    public synchronized void complete(T result) {
        if (completed) {
            return;
        }
        this.result = result;
        completed = true;
        notifyAll();
    }

    // Marks the command as failed.
    public synchronized void fail(RuntimeException failure) {
        if (completed) {
            return;
        }
        this.failure = failure;
        completed = true;
        notifyAll();
    }

    // Waits until the command completes.
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
