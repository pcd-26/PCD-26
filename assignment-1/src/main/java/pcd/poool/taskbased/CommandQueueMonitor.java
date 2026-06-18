package pcd.poool.taskbased;

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

    synchronized boolean put(GameCommand command) {
        if (closed) {
            return false;
        }
        commands.addLast(command);
        notifyAll();
        return true;
    }

    synchronized GameCommand poll() {
        if (commands.isEmpty()) {
            return null;
        }
        return commands.removeFirst();
    }

    synchronized void close() {
        closed = true;
        while (!commands.isEmpty()) {
            commands.removeFirst().reject();
        }
        notifyAll();
    }
}
