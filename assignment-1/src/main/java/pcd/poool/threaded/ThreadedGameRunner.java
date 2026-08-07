package pcd.poool.threaded;

import java.time.Duration;
import pcd.poool.model.common.math.V2d;
import pcd.poool.model.physics.common.BoardConf;
import pcd.poool.model.physics.threaded.ThreadedPhysicsEngine;
import pcd.poool.runtime.BotAgent;
import pcd.poool.runtime.CommandReceiptSupport;
import pcd.poool.runtime.GameLoop;
import pcd.poool.runtime.GameRuntime;
import pcd.poool.runtime.GameRuntimeConfig;
import pcd.poool.runtime.RuntimeGameSnapshot;
import pcd.poool.runtime.SnapshotStoreSupport;

/** Platform-thread driver around the shared {@link GameLoop}. */
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
        loop = new GameLoop(boardConf, physics, config.startupCountdown());
    }

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
                    new BotAgent(loop::snapshot, loop::shootBot, this::isRunning, config.botThinkTimeMillis()),
                    "poool-threaded-bot");
            botThread.start();
        }
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public CommandReceiptSupport<Boolean> shootHuman(V2d velocity) {
        return loop.shootHuman(velocity);
    }

    public CommandReceiptSupport<Boolean> shootBot() {
        return loop.shootBot();
    }

    @Override
    public RuntimeGameSnapshot snapshot() {
        return loop.snapshot();
    }

    public SnapshotStoreSupport<RuntimeGameSnapshot> snapshots() {
        return loop.snapshots();
    }

    public void requestStop() {
        running = false;
        loop.close();
        interrupt(botThread);
        interrupt(controllerThread);
    }

    public void awaitTermination(Duration timeout) throws InterruptedException {
        join(controllerThread, timeout);
        join(botThread, timeout);
        physics.close();
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

    private void runController() {
        try {
            while (running) {
                loop.tick(config.tickMillis());
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
