package pcd.poool.threaded;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;
import pcd.poool.runtime.BotAgent;
import pcd.poool.runtime.CommandMailbox;
import pcd.poool.runtime.GameLoop;
import pcd.poool.runtime.GameRuntime;
import pcd.poool.runtime.GameRuntimeConfig;
import pcd.poool.runtime.RuntimeGameSnapshot;

/**
 * Platform-thread driver around the shared {@link GameLoop}.
 *
 * <p>The controller and bot run on dedicated platform threads, while the
 * physics engine owns the worker threads used for the parallel step.
 */
public final class ThreadedGameRunner implements GameRuntime {

    private static final Duration DEFAULT_JOIN_TIMEOUT = Duration.ofSeconds(2);

    private final ThreadedPhysicsEngine physics;
    private final GameLoop loop;
    private final GameRuntimeConfig config;
    private Thread controllerThread;
    private Thread botThread;
    private volatile boolean running;

    public ThreadedGameRunner(BoardConf boardConf) {
        this(boardConf, GameRuntimeConfig.defaultConfig());
    }

    public ThreadedGameRunner(BoardConf boardConf, GameRuntimeConfig config) {
        this.config = config;
        physics = new ThreadedPhysicsEngine(config.physicsWorkerCount(), config.tickMillis());
        loop = new GameLoop(
                boardConf, // Board layout and initial ball placement.
                physics, // Parallel physics implementation.
                config.startupCountdown()); // Startup shoot lock policy.
    }

    // Starts the controller thread and, when enabled, the bot thread.
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        controllerThread = new Thread(this::runController, "poool-threaded-controller");
        controllerThread.start();
        if (config.botEnabled()) {
            botThread = new Thread(
                    new BotAgent(
                            loop::snapshot, // Reads the latest published game snapshot.
                            loop::shootBot, // Submits the bot shot command.
                            this::isRunning, // Stops the bot when the runtime is no longer active.
                            config.botThinkTimeMillis()), // Delay before the bot shoots.
                    "poool-threaded-bot");
            botThread.start();
        }
    }

    // Exposes whether the controller loop is still active.
    public boolean isRunning() {
        return running;
    }

    @Override
    public CompletableFuture<Boolean> shootHuman(V2d velocity) {
        return loop.shootHuman(velocity);
    }

    public CompletableFuture<Boolean> shootBot() {
        return loop.shootBot();
    }

    @Override
    public RuntimeGameSnapshot snapshot() {
        return loop.snapshot();
    }

    @Override
    public RuntimeGameSnapshot awaitSnapshot(
            Predicate<RuntimeGameSnapshot> condition,
            Duration timeout) throws InterruptedException {
        return loop.awaitSnapshot(condition, timeout);
    }

    // Asks all live threads to stop and rejects further work.
    public void requestStop() {
        running = false;
        loop.close();
        interrupt(botThread);
        interrupt(controllerThread);
    }

    // Waits for the controller, the bot, and the physics workers to finish.
    public void awaitTermination(Duration timeout) throws InterruptedException {
        join(controllerThread, timeout);
        join(botThread, timeout);
        physics.close();
    }

    // Stops the runtime and waits briefly for it to terminate.
    @Override
    public void close() {
        requestStop();
        try {
            awaitTermination(DEFAULT_JOIN_TIMEOUT);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void runController() {
        try {
            // The controller alternates between advancing the game and sleeping
            // for one logical tick duration.
            while (running) {
                // Advance the shared game state.
                loop.tick(config.tickMillis());
                // Keep the controller pacing aligned with the configured tick.
                Thread.sleep(config.tickMillis());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            loop.close();
        }
    }

    private static void join(Thread thread, Duration timeout) throws InterruptedException {
        if (thread != null) {
            thread.join(timeout.toMillis());
        }
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }
}
