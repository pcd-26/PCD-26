package pcd.poool.runtime;

import java.util.LinkedList;

/**
 * Monitor used by producer threads to submit commands to a single controller
 * loop.
 *
 * @param <C> command type
 */
public class CommandQueueMonitorSupport<C extends GameCommand> {

    private final LinkedList<C> commands = new LinkedList<>();
    private boolean closed;

    public CommandQueueMonitorSupport() {
    }

    /**
     * Enqueues a command unless the monitor has been closed.
     *
     * @param command command to execute on the controller thread
     * @return {@code true} if the command was accepted
     */
    public synchronized boolean put(C command) {
        if (closed) {
            return false;
        }
        commands.addLast(command);
        notifyAll();
        return true;
    }

    /**
     * Retrieves one pending command without blocking.
     *
     * @return next command, or {@code null} when the queue is empty
     */
    public synchronized C poll() {
        if (commands.isEmpty()) {
            return null;
        }
        return commands.removeFirst();
    }

    /**
     * Closes the monitor and rejects all commands that were accepted but not
     * yet executed by the controller.
     */
    public synchronized void close() {
        closed = true;
        while (!commands.isEmpty()) {
            commands.removeFirst().reject();
        }
        notifyAll();
    }
}
