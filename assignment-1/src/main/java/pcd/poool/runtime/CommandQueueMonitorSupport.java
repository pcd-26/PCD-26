package pcd.poool.runtime;

import java.util.LinkedList;

// Monitor used by producer threads to submit commands to a controller loop.
public class CommandQueueMonitorSupport<C extends GameCommand> {

    private final LinkedList<C> commands = new LinkedList<>();
    private boolean closed;

    public CommandQueueMonitorSupport() {
    }

    // Enqueues a command unless the monitor has been closed.
    public synchronized boolean put(C command) {
        if (closed) {
            return false;
        }
        commands.addLast(command);
        notifyAll();
        return true;
    }

    // Retrieves one pending command without blocking.
    public synchronized C poll() {
        if (commands.isEmpty()) {
            return null;
        }
        return commands.removeFirst();
    }

    // Closes the monitor and rejects pending commands.
    public synchronized void close() {
        closed = true;
        while (!commands.isEmpty()) {
            commands.removeFirst().reject();
        }
        notifyAll();
    }
}
