package pcd.poool.taskbased;

import pcd.poool.runtime.RuntimeGameSnapshot;
import pcd.poool.runtime.SnapshotStoreSupport;

/**
 * Active bot component that observes immutable snapshots and submits bot-shot
 * commands asynchronously.
 */
class TaskBasedBotAgent implements Runnable {

    private static final long IDLE_SLEEP_MILLIS = 5;

    private final SnapshotStoreSupport<RuntimeGameSnapshot> snapshotStore;
    private final TaskBasedGameRunner runner;
    private final long thinkTimeMillis;

    TaskBasedBotAgent(
            SnapshotStoreSupport<RuntimeGameSnapshot> snapshotStore,
            TaskBasedGameRunner runner,
            long thinkTimeMillis) {
        this.snapshotStore = snapshotStore;
        this.runner = runner;
        this.thinkTimeMillis = thinkTimeMillis;
    }

    @Override
    public void run() {
        long readySince = 0;
        while (runner.isRunning()) {
            var snapshot = snapshotStore.get().game();
            long now = System.currentTimeMillis();
            if (!snapshot.isFinished() && snapshot.botCanShoot()) {
                if (readySince == 0) {
                    readySince = now;
                } else if (now - readySince >= thinkTimeMillis) {
                    runner.shootBot();
                    readySince = 0;
                }
            } else {
                readySince = 0;
            }
            sleepIdle();
        }
    }

    private void sleepIdle() {
        try {
            Thread.sleep(IDLE_SLEEP_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
