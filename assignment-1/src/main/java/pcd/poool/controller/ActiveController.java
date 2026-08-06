package pcd.poool.controller;

import java.util.logging.Level;
import java.util.logging.Logger;
import pcd.poool.model.concurrent.BoundedBuffer;
import pcd.poool.model.concurrent.BoundedBufferImpl;

/**
 * Active controller backed by a blocking command queue.
 *
 * @param <T> target type handled by this controller
 */
public class ActiveController<T> extends Thread {

    private static final Logger LOGGER = Logger.getLogger(ActiveController.class.getName());

    private final BoundedBuffer<Cmd<T>> cmdBuffer;
    private final T target;
    private volatile boolean running;

    public ActiveController(T target, int queueSize) {
        this.cmdBuffer = new BoundedBufferImpl<>(queueSize);
        this.target = target;
        this.running = true;
    }

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

    public void notifyNewCmd(Cmd<T> cmd) {
        try {
            cmdBuffer.put(cmd);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log("interrupted while enqueueing command; command was not queued");
        }
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    private void log(String msg) {
        LOGGER.info(msg);
    }
}
