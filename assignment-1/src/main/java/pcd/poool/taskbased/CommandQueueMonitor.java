package pcd.poool.taskbased;

/**
 * Monitor used by producer threads to submit commands to the single game
 * controller thread.
 */
class CommandQueueMonitor extends pcd.poool.runtime.CommandQueueMonitorSupport<GameCommand> {

    CommandQueueMonitor() {
    }
}
