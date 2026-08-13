package pcd.poool.runtime;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// Active bot component shared by concurrent runners.
public class BotAgent implements Runnable {

    private static final long IDLE_SLEEP_MILLIS = 5;

    private final Supplier<RuntimeGameSnapshot> snapshots;
    private final Runnable submitBotShot;
    private final BooleanSupplier running;
    private final long thinkTimeMillis;

    // Creates a bot agent that observes snapshots and submits bot shots.
    public BotAgent(
            Supplier<RuntimeGameSnapshot> snapshots,
            Runnable submitBotShot,
            BooleanSupplier running,
            long thinkTimeMillis) {
        this.snapshots = snapshots;
        this.submitBotShot = submitBotShot;
        this.running = running;
        this.thinkTimeMillis = thinkTimeMillis;
    }

    @Override
    public void run() {
        long readySince = 0;
        while (running.getAsBoolean()) {
            var snapshot = snapshots.get().game();
            long now = System.currentTimeMillis();
            if (!snapshot.isFinished() && snapshot.botCanShoot()) {
                if (readySince == 0) {
                    readySince = now;
                } else if (now - readySince >= thinkTimeMillis) {
                    submitBotShot.run();
                    readySince = 0;
                }
            } else {
                readySince = 0;
            }
            sleepIdle();
        }
    }

    // Sleeps briefly between bot polling iterations.
    private void sleepIdle() {
        try {
            Thread.sleep(IDLE_SLEEP_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
