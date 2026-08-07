package pcd.poool.taskbased;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.game.GameModel;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.common.PhysicsDefaults;
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;
import pcd.poool.runtime.BotAgent;
import pcd.poool.runtime.CommandQueueMonitorSupport;
import pcd.poool.runtime.CommandReceiptSupport;
import pcd.poool.runtime.CommandSubmissionSupport;
import pcd.poool.runtime.GameCommand;
import pcd.poool.runtime.RuntimeGameSnapshot;
import pcd.poool.runtime.SnapshotStoreSupport;

/**
 * Executor-based execution strategy for Poool.
 *
 * <p>The runner keeps the game model single-writer: the scheduled controller
 * tick is the only component that applies queued commands and advances the
 * simulation. Auxiliary tasks may submit commands through a monitor-backed
 * queue, but they never mutate the model directly.
 */
public class TaskBasedGameRunner implements AutoCloseable {

    private static final Duration DEFAULT_JOIN_TIMEOUT = Duration.ofSeconds(2);

    private final TaskBasedPhysicsEngine physicsEngine;
    private final GameModel game;
    private final Config config;
    private final CommandQueueMonitorSupport<GameCommand> commands;
    private final SnapshotStoreSupport<RuntimeGameSnapshot> snapshots;
    private final ScheduledExecutorService controllerExecutor;
    private final ExecutorService botExecutor;
    private final AtomicReference<RuntimeException> failure;
    private volatile boolean running;
    private volatile boolean closed;

    /**
     * Creates a task-based runner with the default configuration.
     *
     * @param boardConf initial board configuration
     */
    public TaskBasedGameRunner(BoardConf boardConf) {
        this(boardConf, Config.defaultConfig());
    }

    /**
     * Creates a task-based runner.
     *
     * @param boardConf initial board configuration
     * @param config execution configuration
     */
    public TaskBasedGameRunner(BoardConf boardConf, Config config) {
        this(boardConf, config, new TaskBasedPhysicsEngine(config.physicsWorkerCount(), config.tickMillis()));
    }

    TaskBasedGameRunner(BoardConf boardConf, Config config, TaskBasedPhysicsEngine physicsEngine) {
        this.config = Objects.requireNonNull(config, "config");
        this.physicsEngine = Objects.requireNonNull(physicsEngine, "physicsEngine");
        game = new GameModel(
                Objects.requireNonNull(boardConf, "boardConf"),
                physicsEngine,
                config.startupCountdown());
        commands = new CommandQueueMonitorSupport<>();
        snapshots = new SnapshotStoreSupport<>(RuntimeGameSnapshot.from(game));
        failure = new AtomicReference<>();
        controllerExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "poool-task-controller");
            thread.setDaemon(true);
            return thread;
        });
        botExecutor = config.botEnabled()
                ? Executors.newSingleThreadExecutor(runnable -> {
                    var thread = new Thread(runnable, "poool-task-bot");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
    }

    /**
     * Starts the controller loop and, if enabled, the asynchronous bot loop.
     */
    public synchronized void start() {
        ensureHealthy();
        if (running) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("task-based game runner is closed");
        }
        running = true;
        controllerExecutor.scheduleAtFixedRate(this::tick, 0, config.tickMillis(), TimeUnit.MILLISECONDS);
        if (botExecutor != null) {
            botExecutor.submit(new BotAgent(snapshots::get, () -> shootBot(), this::isRunning, config.botThinkTimeMillis()));
        }
    }

    /**
     * Checks whether the runtime is active.
     *
     * @return whether the task-based runtime is active
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
        ensureHealthy();
        return CommandSubmissionSupport.submit(commands, game -> game.shootHuman(velocity), false);
    }

    /**
     * Submits an asynchronous bot shot command.
     *
     * @return receipt completed when the controller executes the command
     */
    public CommandReceiptSupport<Boolean> shootBot() {
        ensureHealthy();
        return CommandSubmissionSupport.submit(commands, GameModel::shootBot, false);
    }

    /**
     * Gets the latest snapshot.
     *
     * @return latest immutable state published by the controller
     */
    public RuntimeGameSnapshot snapshot() {
        ensureHealthy();
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
    public synchronized void requestStop() {
        running = false;
        commands.close();
        controllerExecutor.shutdownNow();
        if (botExecutor != null) {
            botExecutor.shutdownNow();
        }
    }

    /**
     * Waits for executor termination.
     *
     * @param timeout maximum wait duration for each executor
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitTermination(Duration timeout) throws InterruptedException {
        controllerExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (botExecutor != null) {
            botExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        physicsEngine.close();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        requestStop();
        try {
            awaitTermination(DEFAULT_JOIN_TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    RuntimeException failure() {
        return failure.get();
    }

    private void tick() {
        if (!running) {
            return;
        }
        try {
            // Scheduled controller tick: drain commands, advance the model, and publish the next snapshot.
            // One controller thread drains commands, advances physics, and
            // publishes the next immutable snapshot.
            // First execute all pending commands in order.
            drainPendingCommands();
            // Then advance the shared game model by one scheduled tick.
            if (!game.snapshot().isFinished()) {
                game.step(config.tickMillis());
            }
            // Publish the immutable state that the UI and bot will read next.
            snapshots.publish(RuntimeGameSnapshot.from(game));
        } catch (RuntimeException ex) {
            failure.compareAndSet(null, ex);
            running = false;
            commands.close();
            if (botExecutor != null) {
                botExecutor.shutdownNow();
            }
            controllerExecutor.shutdownNow();
            snapshots.publish(RuntimeGameSnapshot.from(game));
            throw ex;
        }
    }

    private void drainPendingCommands() {
        GameCommand command = commands.poll();
        while (command != null) {
            command.execute(game);
            command = commands.poll();
        }
    }

    private void ensureHealthy() {
        var observedFailure = failure.get();
        if (observedFailure != null) {
            throw new IllegalStateException("task-based game runner failed", observedFailure);
        }
    }

    /**
     * Runtime configuration for the task-based runner.
     *
     * @param tickMillis fixed simulation tick duration
     * @param botEnabled whether to start the asynchronous bot agent
     * @param botThinkTimeMillis delay before the bot submits a shot
     * @param physicsWorkerCount number of executor workers used inside each physics step
     * @param startupCountdown startup countdown configuration for the game model
     */
    public record Config(
            long tickMillis,
            boolean botEnabled,
            long botThinkTimeMillis,
            int physicsWorkerCount,
            GameModel.StartupCountdown startupCountdown) {

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
         * Creates a configuration using the default gameplay countdown.
         *
         * @param tickMillis fixed simulation tick duration
         * @param botEnabled whether to start the asynchronous bot agent
         * @param botThinkTimeMillis delay before the bot submits a shot
         * @param physicsWorkerCount number of physics workers
         */
        public Config(long tickMillis, boolean botEnabled, long botThinkTimeMillis, int physicsWorkerCount) {
            this(
                    tickMillis,
                    botEnabled,
                    botThinkTimeMillis,
                    physicsWorkerCount,
                    GameModel.StartupCountdown.enabledDefault());
        }

        /**
         * Creates a configuration using the default physics worker count and an
         * explicit startup countdown policy.
         *
         * @param tickMillis fixed simulation tick duration
         * @param botEnabled whether to start the asynchronous bot agent
         * @param botThinkTimeMillis delay before the bot submits a shot
         * @param startupCountdown startup countdown configuration
         */
        public Config(
                long tickMillis,
                boolean botEnabled,
                long botThinkTimeMillis,
                GameModel.StartupCountdown startupCountdown) {
            this(tickMillis, botEnabled, botThinkTimeMillis, defaultPhysicsWorkerCount(), startupCountdown);
        }

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
            if (startupCountdown == null) {
                throw new IllegalArgumentException("startupCountdown must not be null");
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
            return new Config(
                    PhysicsDefaults.FIXED_STEP_MILLIS,
                    false,
                    0,
                    defaultPhysicsWorkerCount(),
                    GameModel.StartupCountdown.disabled());
        }

        private static int defaultPhysicsWorkerCount() {
            return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        }
    }
}
