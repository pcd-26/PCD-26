package pcd.poool.controller;

import pcd.poool.model.concurrent.BoundedBuffer;
import pcd.poool.model.concurrent.BoundedBufferImpl;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic active controller based on a producer/consumer queue.
 *
 * <p>External producers enqueue {@link Cmd} instances through
 * {@link #notifyNewCmd(Cmd)}. This thread is the single consumer: it takes
 * commands from the bounded buffer and executes them one at a time on the
 * configured target object.
 *
 * <p>The target can be a model object, such as a board, or a higher-level
 * service that owns the game state. This pattern keeps asynchronous inputs
 * ordered and avoids having multiple producer threads mutate the same state
 * directly.
 *
 * @param <T> target state/service type updated by commands
 */
public class ActiveController<T> extends Thread {

    private static final Logger LOGGER = Logger.getLogger(ActiveController.class.getName());

    private final BoundedBuffer<Cmd<T>> cmdBuffer;
    private final T target;
    private volatile boolean running;

    /**
     * Creates an active controller for a target object.
     *
     * @param target object on which commands are executed
     * @param queueSize maximum number of pending commands
     */
    public ActiveController(T target, int queueSize) {
        this.cmdBuffer = new BoundedBufferImpl<>(queueSize);
        this.target = target;
        this.running = true;
    }

    /**
     * Consumes commands until the controller is shut down or interrupted.
     */
    @Override
    public void run() {
        log("started");
        while (running) {
            try {
                Cmd<T> cmd = cmdBuffer.get();
                cmd.execute(target);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, "Exception during command execution", ex);
            }
        }
        log("stopped");
    }

    /**
     * Enqueues a command for asynchronous execution.
     *
     * @param cmd command to execute on the controller target
     */
    public void notifyNewCmd(Cmd<T> cmd) {
        try {
            cmdBuffer.put(cmd);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("interrupted while enqueueing command; command was not queued");
        }
    }

    /**
     * Requests controller termination and interrupts any blocking buffer wait.
     */
    public void shutdown() {
        running = false;
        interrupt();
    }

    private void log(String msg) {
        LOGGER.info(msg);
    }
}
