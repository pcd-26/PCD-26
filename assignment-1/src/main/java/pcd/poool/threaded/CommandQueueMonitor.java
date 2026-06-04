package pcd.poool.threaded;

import java.util.LinkedList;

/**
 * Monitor used by producer threads to submit commands to the single game
 * controller thread.
 */
class CommandQueueMonitor {

    private final LinkedList<GameCommand> commands;
    private boolean closed;

    CommandQueueMonitor() {
        commands = new LinkedList<>();
    }

    /**
     * Enqueues a command unless the monitor has been closed.
     *
     * @param command command to execute on the controller thread
     * @return {@code true} if the command was accepted
     */
    synchronized boolean put(GameCommand command) {
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
    synchronized GameCommand poll() {
        if (commands.isEmpty()) {
            return null;
        }
        return commands.removeFirst();
    }

    /**
     * Closes the monitor and rejects all commands that were accepted but not
     * yet executed by the controller.
     */
    synchronized void close() {
        closed = true;
        while (!commands.isEmpty()) {
            commands.removeFirst().reject();
        }
        notifyAll();
    }
}
