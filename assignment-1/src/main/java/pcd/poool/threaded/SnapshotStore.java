package pcd.poool.threaded;

/**
 * Monitor that stores the latest immutable snapshot published by the
 * simulation controller.
 */
public class SnapshotStore extends pcd.poool.runtime.SnapshotStoreSupport<ThreadedGameSnapshot> {

    SnapshotStore(ThreadedGameSnapshot initialSnapshot) {
        super(initialSnapshot);
    }
}
