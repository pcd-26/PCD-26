package pcd.poool.runtime;

import java.util.function.Function;
import pcd.poool.model.game.GameModel;

/**
 * Helper for submitting model-owned commands to a controller queue.
 */
public final class CommandSubmissionSupport {

    private CommandSubmissionSupport() {
    }

    /**
     * Enqueues a game-model operation and returns a receipt completed by the
     * controller thread or task.
     *
     * @param commands controller-owned command queue
     * @param operation operation executed against the controller-owned game model
     * @param rejectedResult result used when the queue is closed before execution
     * @param <T> operation result type
     * @return receipt completed when the command executes or is rejected
     */
    public static <T> CommandReceiptSupport<T> submit(
            CommandQueueMonitorSupport<GameCommand> commands,
            Function<GameModel, T> operation,
            T rejectedResult) {
        var receipt = new CommandReceiptSupport<T>();
        boolean accepted = commands.put(new GameCommand() {
            @Override
            public void execute(GameModel game) {
                try {
                    receipt.complete(operation.apply(game));
                } catch (RuntimeException ex) {
                    receipt.fail(ex);
                }
            }

            @Override
            public void reject() {
                receipt.complete(rejectedResult);
            }
        });
        if (!accepted) {
            receipt.complete(rejectedResult);
        }
        return receipt;
    }
}
