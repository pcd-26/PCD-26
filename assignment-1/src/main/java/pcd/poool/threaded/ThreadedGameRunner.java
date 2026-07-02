package pcd.poool.threaded;

import java.time.Duration;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;

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
    private final CommandQueueMonitor commands;
    private final SnapshotStore snapshots;
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
        commands = new CommandQueueMonitor();
        snapshots = new SnapshotStore(ThreadedGameSnapshot.from(game));
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
                    new ThreadedBotAgent(snapshots, this, config.botThinkTimeMillis()),
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
    public CommandReceipt<Boolean> shootHuman(V2d velocity) {
        return submitShotCommand(game -> game.shootHuman(velocity));
    }

    /**
     * Submits an asynchronous bot shot command.
     *
     * @return receipt completed when the controller executes the command
     */
    public CommandReceipt<Boolean> shootBot() {
        return submitShotCommand(GameModel::shootBot);
    }

    /**
     * Gets the latest snapshot.
     *
     * @return latest immutable state published by the controller
     */
    public ThreadedGameSnapshot snapshot() {
        return snapshots.get();
    }

    /**
     * Gets the snapshot monitor.
     *
     * @return monitor used by tests and views to wait for published snapshots
     */
    public SnapshotStore snapshots() {
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

    private CommandReceipt<Boolean> submitShotCommand(ShotOperation operation) {
        var receipt = new CommandReceipt<Boolean>();
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

    private void runController() {
        try {
            while (running) {
                drainPendingCommands();
                if (!game.snapshot().isFinished()) {
                    game.step(config.tickMillis());
                }
                snapshots.publish(ThreadedGameSnapshot.from(game));
                sleepTick();
            }
        } finally {
            running = false;
            commands.close();
            snapshots.publish(ThreadedGameSnapshot.from(game));
        }
    }

    private void drainPendingCommands() {
        GameCommand command = commands.poll();
        while (command != null) {
            command.execute(game);
            command = commands.poll();
        }
    }

    private void sleepTick() {
        try {
            Thread.sleep(config.tickMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private void join(Thread thread, Duration timeout) throws InterruptedException {
        if (thread != null) {
            thread.join(timeout.toMillis());
        }
    }

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
