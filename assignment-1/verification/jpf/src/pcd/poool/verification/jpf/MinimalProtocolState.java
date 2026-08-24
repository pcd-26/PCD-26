package pcd.poool.verification.jpf;

/**
 * Shared bounded protocol state for the JPF models.
 *
 * <p>The model abstracts the physics payload but retains the relationships
 * that matter to the production protocol: mailbox draining, controller-owned
 * board access, a two-worker completion barrier, publication, and shutdown.
 */
final class MinimalProtocolState {

    // The scenario is deliberately finite: two submitted commands and two work units.
    static final int COMMAND_COUNT = 2;
    static final int WORKER_COUNT = 2;

    // Every field below is protected by this monitor.
    private final Object lock = new Object();
    // A command moves from submitted to executed exactly once.
    private final boolean[] submitted = new boolean[COMMAND_COUNT];
    private final boolean[] executed = new boolean[COMMAND_COUNT];
    // This is reset for every physics generation, so one worker cannot count twice.
    private final boolean[] workerCompleted = new boolean[WORKER_COUNT];

    // Mailbox state and command-processing result.
    private int pendingCommands;
    private int executedCommands;
    // A generation identifies one dispatched physics phase.
    private int generation;
    private int completedWorkers;
    // Publication is represented by a counter rather than a full game snapshot.
    private int publishedSnapshots;
    // The controller owns the model while one tick is in progress.
    private boolean boardOwned;
    private boolean commandsDrainedForTick;
    // Long-lived workers return from their wait when shutdown is requested.
    private boolean stopping;

    /** Models one asynchronous producer adding a command to the mailbox. */
    void submitCommand(int commandId) {
        synchronized (lock) {
            if (commandId < 0 || commandId >= COMMAND_COUNT || submitted[commandId]) {
                throw new AssertionError("command submitted more than once");
            }
            submitted[commandId] = true;
            pendingCommands++;
            // Wake a controller that may be waiting for the first command.
            lock.notifyAll();
        }
    }

    /** Blocks the controller until at least one command is available. */
    void waitForPendingCommand() throws InterruptedException {
        synchronized (lock) {
            while (pendingCommands == 0) {
                lock.wait();
            }
        }
    }

    /** Starts one controller tick and drains the commands visible at its start. */
    int beginTickAndDrainCommands() {
        synchronized (lock) {
            if (boardOwned) {
                throw new AssertionError("board is already owned");
            }
            if (pendingCommands == 0) {
                throw new AssertionError("tick started without a pending command");
            }
            // From this point until publication, only the controller may commit state.
            boardOwned = true;
            for (int commandId = 0; commandId < COMMAND_COUNT; commandId++) {
                if (submitted[commandId] && !executed[commandId]) {
                    executed[commandId] = true;
                    executedCommands++;
                }
            }
            // Commands arriving after this point belong to the following tick.
            pendingCommands = 0;
            completedWorkers = 0;
            commandsDrainedForTick = true;
            for (int workerId = 0; workerId < WORKER_COUNT; workerId++) {
                workerCompleted[workerId] = false;
            }
            generation++;
            // Wake the persistent workers assigned to this generation.
            lock.notifyAll();
            return generation;
        }
    }

    /** Waits for the next assigned phase in the long-lived-worker model. */
    int awaitNextGeneration(int completedGeneration) throws InterruptedException {
        synchronized (lock) {
            // A worker sleeps until the controller assigns a newer generation.
            while (!stopping && generation <= completedGeneration) {
                lock.wait();
            }
            if (stopping) {
                // A negative generation is the worker's shutdown signal.
                return -1;
            }
            if (!boardOwned) {
                throw new AssertionError("worker accessed board without controller ownership");
            }
            return generation;
        }
    }

    /** Registers one distinct worker completion for the active phase. */
    void completeWorker(int workerId, int completedGeneration) {
        synchronized (lock) {
            if (!boardOwned) {
                throw new AssertionError("worker completed after board ownership was released");
            }
            if (completedGeneration != generation) {
                throw new AssertionError("worker completed the wrong generation");
            }
            if (workerId < 0 || workerId >= WORKER_COUNT || workerCompleted[workerId]) {
                throw new AssertionError("worker completed the phase more than once");
            }
            workerCompleted[workerId] = true;
            completedWorkers++;
            // Wake the controller if this was the last required completion.
            lock.notifyAll();
        }
    }

    /** Models the completion barrier and publishes only a committed tick. */
    void awaitWorkersAndPublish() throws InterruptedException {
        synchronized (lock) {
            // This is the model's equivalent of a completion monitor or Future.get barrier.
            while (completedWorkers < WORKER_COUNT) {
                lock.wait();
            }
            if (!commandsDrainedForTick) {
                throw new AssertionError("snapshot published before commands were drained");
            }
            if (!boardOwned) {
                throw new AssertionError("snapshot published without board ownership");
            }
            // The tick is now complete, so the resulting state may be published.
            publishedSnapshots++;
            boardOwned = false;
            commandsDrainedForTick = false;
            lock.notifyAll();
        }
    }

    /** Lets the controller decide whether the finite verification scenario is complete. */
    boolean allCommandsExecuted() {
        synchronized (lock) {
            return executedCommands == COMMAND_COUNT;
        }
    }

    /** Releases long-lived workers that are blocked waiting for a new generation. */
    void stopWorkers() {
        synchronized (lock) {
            stopping = true;
            lock.notifyAll();
        }
    }

    /** Final JPF assertion: every command was handled and no shared state remains owned. */
    void assertFinalState() {
        synchronized (lock) {
            if (boardOwned) {
                throw new AssertionError("board ownership was not released");
            }
            if (pendingCommands != 0) {
                throw new AssertionError("pending commands remain after completion");
            }
            if (executedCommands != COMMAND_COUNT) {
                throw new AssertionError("not every submitted command was executed exactly once");
            }
            for (int commandId = 0; commandId < COMMAND_COUNT; commandId++) {
                if (!submitted[commandId] || !executed[commandId]) {
                    throw new AssertionError("a command was lost before completion");
                }
            }
            if (publishedSnapshots == 0) {
                throw new AssertionError("no completed tick was published");
            }
            if (!stopping) {
                throw new AssertionError("workers were not stopped");
            }
        }
    }
}
