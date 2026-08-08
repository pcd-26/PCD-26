package pcd.poool.taskbased;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.taskbased.TaskBasedPhysicsEngine;
import pcd.poool.runtime.BotAgent;
import pcd.poool.runtime.CommandMailbox;
import pcd.poool.runtime.GameLoop;
import pcd.poool.runtime.GameRuntime;
import pcd.poool.runtime.GameRuntimeConfig;
import pcd.poool.runtime.RuntimeGameSnapshot;

/**
 * Executor Framework driver around the shared {@link GameLoop}.
 *
 * <p>The controller runs on a scheduled executor, the bot uses a dedicated
 * executor, and the physics engine owns its own worker pool.
 */
public final class TaskBasedGameRunner implements GameRuntime {

    private static final Duration DEFAULT_JOIN_TIMEOUT = Duration.ofSeconds(2);

    private final TaskBasedPhysicsEngine physics;
    private final GameLoop loop;
    private final GameRuntimeConfig config;
    private final ScheduledExecutorService controller;
    private final ExecutorService botExecutor;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private volatile boolean running;
    private volatile boolean closed;

    public TaskBasedGameRunner(BoardConf boardConf) {
        this(boardConf, GameRuntimeConfig.defaultConfig());
    }

    public TaskBasedGameRunner(BoardConf boardConf, GameRuntimeConfig config) {
        this(boardConf, config, new TaskBasedPhysicsEngine(config.physicsWorkerCount(), config.tickMillis()));
    }

    TaskBasedGameRunner(BoardConf boardConf, GameRuntimeConfig config, TaskBasedPhysicsEngine physics) {
        this.config = Objects.requireNonNull(config, "config");
        this.physics = Objects.requireNonNull(physics, "physics");
        loop = new GameLoop(
                boardConf, // Board layout and initial ball placement.
                physics, // Parallel physics implementation.
                config.startupCountdown()); // Startup shoot lock policy.
        controller = Executors.newSingleThreadScheduledExecutor(runnable -> namedThread(runnable, "poool-task-controller"));
        botExecutor = config.botEnabled()
                ? Executors.newSingleThreadExecutor(runnable -> namedThread(runnable, "poool-task-bot"))
                : null;
    }

    // Schedules the controller tick and submits the bot loop if enabled.
    @Override
    public synchronized void start() {
        ensureHealthy();
        if (running) {
            return;
        }
        if (closed) {
            throw new IllegalStateException("task-based game runner is closed");
        }
        running = true;
        controller.scheduleAtFixedRate(this::tick, 0, config.tickMillis(), TimeUnit.MILLISECONDS);
        if (botExecutor != null) {
            botExecutor.submit(new BotAgent(
                    loop::snapshot, // Reads the latest published game snapshot.
                    loop::shootBot, // Submits the bot shot command.
                    this::isRunning, // Stops the bot when the runtime is no longer active.
                    config.botThinkTimeMillis())); // Delay before the bot shoots.
        }
    }

    // Exposes whether the scheduled controller is still active.
    public boolean isRunning() {
        return running;
    }

    @Override
    public CommandMailbox.Receipt<Boolean> shootHuman(V2d velocity) {
        ensureHealthy();
        return loop.shootHuman(velocity);
    }

    public CommandMailbox.Receipt<Boolean> shootBot() {
        ensureHealthy();
        return loop.shootBot();
    }

    @Override
    public RuntimeGameSnapshot snapshot() {
        ensureHealthy();
        return loop.snapshot();
    }

    @Override
    public RuntimeGameSnapshot awaitSnapshot(
            Predicate<RuntimeGameSnapshot> condition,
            Duration timeout) throws InterruptedException {
        return loop.awaitSnapshot(condition, timeout);
    }

    // Stops the loop and shuts down all owned executors.
    public synchronized void requestStop() {
        running = false;
        loop.close();
        controller.shutdownNow();
        if (botExecutor != null) {
            botExecutor.shutdownNow();
        }
    }

    // Waits for the controller, the bot, and the physics workers to finish.
    public void awaitTermination(Duration timeout) throws InterruptedException {
        controller.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (botExecutor != null) {
            botExecutor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        physics.close();
    }

    // Marks the runner closed and performs a best-effort stop.
    @Override
    public synchronized void close() {
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
            // One scheduled tick advances the whole match through GameLoop.
            loop.tick(config.tickMillis());
        } catch (RuntimeException ex) {
            if (failure.compareAndSet(null, ex)) {
                running = false;
                loop.close();
                controller.shutdown();
                if (botExecutor != null) {
                    botExecutor.shutdownNow();
                }
            }
        }
    }

    private void ensureHealthy() {
        var cause = failure.get();
        if (cause != null) {
            throw new IllegalStateException("task-based game runner failed", cause);
        }
    }

    private static Thread namedThread(Runnable runnable, String name) {
        var thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
