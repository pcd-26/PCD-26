package pcd.poool.threaded;

import java.time.Duration;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;
import pcd.poool.runtime.BotAgent;
import pcd.poool.runtime.CommandQueueMonitorSupport;
import pcd.poool.runtime.CommandReceiptSupport;
import pcd.poool.runtime.GameCommand;
import pcd.poool.runtime.RuntimeGameSnapshot;
import pcd.poool.runtime.SnapshotStoreSupport;

/**
 * Platform-thread execution strategy for Poool.
 *
 * <p>The runner owns a {@link GameModel} instance and confines all game
 * mutations to one controller thread. External threads submit commands through
 * a monitor, while the optional bot agent runs as a separate platform thread.
 */
public class ThreadedGameRunner implements AutoCloseable {

    private static final Duration DEFAULT_JOIN_TIMEOUT = Duration.ofSeconds(2);

    private final ThreadedPhysicsEngine physicsEngine;
    private final GameModel game;
    private final Config config;
    private final CommandQueueMonitorSupport<GameCommand> commands;
    private final SnapshotStoreSupport<RuntimeGameSnapshot> snapshots;
    private Thread controllerThread;
    private Thread botThread;
    private volatile boolean running;

    /**
     * Creates a threaded runner with the default configuration.
     *
     * @param boardConf initial board configuration
     */
    public ThreadedGameRunner(BoardConf boardConf) {
        this(boardConf, Config.defaultConfig());
    }

    /**
     * Creates a threaded runner.
     *
     * @param boardConf initial board configuration
     * @param config execution configuration
     */
    public ThreadedGameRunner(BoardConf boardConf, Config config) {
        this.config = config;
        physicsEngine = new ThreadedPhysicsEngine(config.physicsWorkerCount(), config.tickMillis());
        game = new GameModel(boardConf, physicsEngine);
        commands = new CommandQueueMonitorSupport<>();
        snapshots = new SnapshotStoreSupport<>(RuntimeGameSnapshot.from(game));
    }

    /**
     * Starts the controller thread and, if enabled, the bot thread.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        controllerThread = new Thread(this::runController, "poool-threaded-controller");
        controllerThread.start();
        if (config.botEnabled()) {
            botThread = new Thread(
                    new BotAgent(snapshots::get, () -> shootBot(), this::isRunning, config.botThinkTimeMillis()),
                    "poool-threaded-bot");
            botThread.start();
        }
    }

    /**
     * Checks whether the runtime is active.
     *
     * @return whether the threaded runtime is active
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Submits an asynchronous human shot command.
     *
     * @param velocity shot velocity
     * @return receipt completed when the controller executes the command
     */
    public CommandReceiptSupport<Boolean> shootHuman(V2d velocity) {
        return submitShotCommand(game -> game.shootHuman(velocity));
    }

    /**
     * Submits an asynchronous bot shot command.
     *
     * @return receipt completed when the controller executes the command
     */
    public CommandReceiptSupport<Boolean> shootBot() {
        return submitShotCommand(GameModel::shootBot);
    }

    /**
     * Gets the latest snapshot.
     *
     * @return latest immutable state published by the controller
     */
    public RuntimeGameSnapshot snapshot() {
        return snapshots.get();
    }

    /**
     * Gets the snapshot monitor.
     *
     * @return monitor used by tests and views to wait for published snapshots
     */
    public SnapshotStoreSupport<RuntimeGameSnapshot> snapshots() {
        return snapshots;
    }

    /**
     * Requests runtime shutdown and wakes blocked producers.
     */
    public void requestStop() {
        running = false;
        commands.close();
        interrupt(botThread);
        interrupt(controllerThread);
    }

    /**
     * Waits for platform threads to terminate.
     *
     * @param timeout maximum wait duration for each thread
     * @throws InterruptedException if interrupted while joining
     */
    public void awaitTermination(Duration timeout) throws InterruptedException {
        join(controllerThread, timeout);
        join(botThread, timeout);
        physicsEngine.close();
    }

    @Override
    public void close() {
        requestStop();
        try {
            awaitTermination(DEFAULT_JOIN_TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Submits a shot operation asynchronously to the queue.
     *
     * <p>Wraps the operation into a {@link GameCommand} object, enqueues it,
     * and returns an incomplete {@link CommandReceiptSupport}. When the controller thread
     * consumes the command from the queue, it executes the operation, completing
     * the receipt with the result or any failure that occurred.
     *
     * @param operation the shot operation to execute on the game model
     * @return the command receipt representing completion of the request
     */
    private CommandReceiptSupport<Boolean> submitShotCommand(ShotOperation operation) {
        var receipt = new CommandReceiptSupport<Boolean>();
        boolean accepted = commands.put(new GameCommand() {
            @Override
            public void execute(GameModel game) {
                try {
                    receipt.complete(operation.execute(game));
                } catch (RuntimeException ex) {
                    receipt.fail(ex);
                }
            }

            @Override
            public void reject() {
                receipt.complete(false);
            }
        });
        if (!accepted) {
            receipt.complete(false);
        }
        return receipt;
    }

    /**
     * The main execution loop of the platform-thread controller.
     *
     * <p>As long as the runner is active, this thread:
     * <ol>
     *   <li>Drains and executes all pending shot commands from external threads (e.g. Swing Event Dispatch Thread, Bot Thread).</li>
     *   <li>Advances the game step and physics simulation by a fixed tick duration.</li>
     *   <li>Publishes the new immutable snapshot to the snapshot store.</li>
     *   <li>Sleeps for the fixed tick interval to maintain frame rate.</li>
     * </ol>
     */
    private void runController() {
        try {
            while (running) {
                // The controller serializes command execution and rule updates;
                // worker threads are confined to the physics engine internals.
                drainPendingCommands();
                if (!game.snapshot().isFinished()) {
                    game.step(config.tickMillis());
                }
                snapshots.publish(RuntimeGameSnapshot.from(game));
                sleepTick();
            }
        } finally {
            running = false;
            commands.close();
            snapshots.publish(RuntimeGameSnapshot.from(game));
        }
    }

    /**
     * Non-blockingly polls and executes all pending commands in the queue.
     *
     * <p>This executes commands sequentially on the controller thread, guaranteeing
     * thread-confinement of the game model state.
     */
    private void drainPendingCommands() {
        GameCommand command = commands.poll();
        while (command != null) {
            command.execute(game);
            command = commands.poll();
        }
    }

    /**
     * Puts the controller thread to sleep for the tick duration.
     *
     * <p>If interrupted, the interrupt flag is restored and the controller terminates.
     */
    private void sleepTick() {
        try {
            // The controller thread stays independent from Swing repainting.
            Thread.sleep(config.tickMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    /**
     * Joins the given thread up to a maximum duration.
     *
     * @param thread the thread to join
     * @param timeout the maximum duration to wait
     * @throws InterruptedException if interrupted while joining
     */
    private void join(Thread thread, Duration timeout) throws InterruptedException {
        if (thread != null) {
            thread.join(timeout.toMillis());
        }
    }

    /**
     * Interrupts the given thread if it is not null.
     *
     * @param thread the thread to interrupt
     */
    private void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    @FunctionalInterface
    private interface ShotOperation {

        boolean execute(GameModel game);
    }

    /**
     * Runtime configuration for the platform-thread runner.
     *
     * @param tickMillis fixed simulation tick duration
     * @param botEnabled whether to start the asynchronous bot agent
     * @param botThinkTimeMillis delay before the bot submits a shot
     * @param physicsWorkerCount number of worker platform threads used inside
     *        each physics step
     */
    public record Config(long tickMillis, boolean botEnabled, long botThinkTimeMillis, int physicsWorkerCount) {

        /**
         * Creates a configuration using the default physics worker count.
         *
         * @param tickMillis fixed simulation tick duration
         * @param botEnabled whether to start the asynchronous bot agent
         * @param botThinkTimeMillis delay before the bot submits a shot
         */
        public Config(long tickMillis, boolean botEnabled, long botThinkTimeMillis) {
            this(tickMillis, botEnabled, botThinkTimeMillis, defaultPhysicsWorkerCount());
        }

        /**
         * Validates the runtime configuration.
         */
        public Config {
            if (tickMillis <= 0) {
                throw new IllegalArgumentException("tickMillis must be > 0");
            }
            if (botThinkTimeMillis < 0) {
                throw new IllegalArgumentException("botThinkTimeMillis must be >= 0");
            }
            if (physicsWorkerCount < 1) {
                throw new IllegalArgumentException("physicsWorkerCount must be >= 1");
            }
        }

        /**
         * Creates the default runtime configuration.
         *
         * @return default runtime configuration
         */
        public static Config defaultConfig() {
            return new Config(PhysicsDefaults.FIXED_STEP_MILLIS, true, 600, defaultPhysicsWorkerCount());
        }

        /**
         * Creates a configuration suitable for deterministic tests without bot
         * activity.
         *
         * @return configuration without the asynchronous bot agent
         */
        public static Config withoutBot() {
            return new Config(PhysicsDefaults.FIXED_STEP_MILLIS, false, 0, defaultPhysicsWorkerCount());
        }

        private static int defaultPhysicsWorkerCount() {
            return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        }
    }
}
