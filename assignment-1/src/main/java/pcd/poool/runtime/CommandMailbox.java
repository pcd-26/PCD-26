package pcd.poool.runtime;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import pcd.poool.model.game.GameModel;

/** Monitor that queues model commands and completes their asynchronous result. */
public final class CommandMailbox {

    private final Queue<Command<?>> commands = new ArrayDeque<>();
    private boolean closed;

    /** Queues one operation or immediately rejects it after shutdown. */
    public <T> CompletableFuture<T> submit(Function<GameModel, T> operation, T rejectedResult) {
        var completion = new CompletableFuture<T>();
        synchronized (this) {
            if (closed) {
                completion.complete(rejectedResult);
            } else {
                commands.add(new Command<>(operation, completion, rejectedResult));
            }
        }
        return completion;
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
            CompletableFuture<T> completion,
            T rejectedResult) {

        private void execute(GameModel game) {
            try {
                completion.complete(operation.apply(game));
            } catch (RuntimeException ex) {
                completion.completeExceptionally(ex);
            }
        }

        private void reject() {
            completion.complete(rejectedResult);
        }
    }
}
