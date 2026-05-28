package pcd.poool.controller;

import pcd.poool.model.concurrent.BoundedBuffer;
import pcd.poool.model.concurrent.BoundedBufferImpl;

/**
 * Generic active controller based on a producer/consumer queue.
 * Producers enqueue commands, this thread consumes and executes them.
 *
 * @param <T> target state/service type updated by commands
 */
public class ActiveController<T> extends Thread {

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
                ex.printStackTrace();
            }
        }
        log("stopped");
    }

    public void notifyNewCmd(Cmd<T> cmd) {
        try {
            cmdBuffer.put(cmd);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    private void log(String msg) {
        System.out.println("[" + System.currentTimeMillis() + "][ActiveController] " + msg);
    }
}
