package pcd.poool.runtime;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// Active bot component shared by concurrent runners.
public class BotAgent implements Runnable {

    private static final Duration READY_WAIT_TIMEOUT = Duration.ofMillis(250);

    private final Supplier<RuntimeGameSnapshot> snapshots;
    private final SnapshotAwaiter awaitSnapshots;
    private final Runnable submitBotShot;
    private final BooleanSupplier running;
    private final long thinkTimeMillis;

    // Creates a bot agent that observes snapshots and submits bot shots.
    public BotAgent(
            Supplier<RuntimeGameSnapshot> snapshots,
            SnapshotAwaiter awaitSnapshots,
            Runnable submitBotShot,
            BooleanSupplier running,
            long thinkTimeMillis) {
        this.snapshots = snapshots;
        this.awaitSnapshots = awaitSnapshots;
        this.submitBotShot = submitBotShot;
        this.running = running;
        this.thinkTimeMillis = thinkTimeMillis;
    }

    @Override
    public void run() {
        while (running.getAsBoolean()) {
            try {
                var snapshot = snapshots.get();
                if (!isReadyToShoot(snapshot)) {
                    snapshot = awaitSnapshots.await(this::isReadyToShoot, READY_WAIT_TIMEOUT);
                }
                if (!running.getAsBoolean()) {
                    break;
                }
                sleepThinkTime();
                if (!running.getAsBoolean()) {
                    break;
                }
                if (isReadyToShoot(snapshots.get())) {
                    submitBotShot.run();
                }
            } catch (IllegalStateException ignored) {
                // Timeout while waiting for a ready snapshot: re-check the stop flag and try again.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean isReadyToShoot(RuntimeGameSnapshot snapshot) {
        var game = snapshot.game();
        return !game.isFinished() && game.botCanShoot();
    }

    private void sleepThinkTime() throws InterruptedException {
        if (thinkTimeMillis > 0) {
            Thread.sleep(thinkTimeMillis);
        }
    }

    @FunctionalInterface
    public interface SnapshotAwaiter {
        RuntimeGameSnapshot await(
                Predicate<RuntimeGameSnapshot> condition,
                Duration timeout) throws InterruptedException;
    }
}
