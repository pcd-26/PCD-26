package pcd.poool.runtime;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Function;
import pcd.poool.model.game.GameModel;

/** Monitor that queues model commands and completes their receipts. */
public final class CommandMailbox {

    private final Queue<Command<?>> commands = new ArrayDeque<>();
    private boolean closed;

    /** Queues one operation or immediately rejects it after shutdown. */
    public <T> Receipt<T> submit(Function<GameModel, T> operation, T rejectedResult) {
        var receipt = new Receipt<T>();
        synchronized (this) {
            if (closed) {
                receipt.complete(rejectedResult);
            } else {
                commands.add(new Command<>(operation, receipt, rejectedResult));
            }
        }
        return receipt;
    }

    /** Executes every pending operation on the controller-owned model. */
    public void drain(GameModel game) {
        Command<?> command;
        while ((command = poll()) != null) {
            command.execute(game);
        }
    }

    /** Rejects pending and future commands. */
    public synchronized void close() {
        closed = true;
        Command<?> command;
        while ((command = commands.poll()) != null) {
            command.reject();
        }
        notifyAll();
    }

    private synchronized Command<?> poll() {
        return commands.poll();
    }

    private record Command<T>(
            Function<GameModel, T> operation,
            Receipt<T> receipt,
            T rejectedResult) {

        private void execute(GameModel game) {
            try {
                receipt.complete(operation.apply(game));
            } catch (RuntimeException ex) {
                receipt.fail(ex);
            }
        }

        private void reject() {
            receipt.complete(rejectedResult);
        }
    }

    /** Completion handle returned to an asynchronous command producer. */
    public static final class Receipt<T> {

        private boolean completed;
        private T result;
        private RuntimeException failure;

        private synchronized void complete(T result) {
            if (!completed) {
                this.result = result;
                completed = true;
                notifyAll();
            }
        }

        private synchronized void fail(RuntimeException failure) {
            if (!completed) {
                this.failure = failure;
                completed = true;
                notifyAll();
            }
        }

        /** Waits for completion or throws when the timeout expires. */
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
}
