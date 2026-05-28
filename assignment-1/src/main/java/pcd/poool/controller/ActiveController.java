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

    public ActiveController(T target, int queueSize) {
        this.cmdBuffer = new BoundedBufferImpl<>(queueSize);
        this.target = target;
    }

    @Override
    public void run() {
        log("started");
        while (true) {
            try {
                Cmd<T> cmd = cmdBuffer.get();
                cmd.execute(target);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    public void notifyNewCmd(Cmd<T> cmd) {
        try {
            cmdBuffer.put(cmd);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void log(String msg) {
        System.out.println("[" + System.currentTimeMillis() + "][ActiveController] " + msg);
    }
}
