package pcd.poool.threaded;

/**
 * Monitor that stores the latest immutable snapshot published by the
 * simulation controller.
 */
public class SnapshotStore extends pcd.poool.runtime.SnapshotStoreSupport<ThreadedGameSnapshot> {

    /**
     * Creates a SnapshotStore initialized with the given starting game snapshot.
     *
     * @param initialSnapshot the starting immutable game snapshot
     */
    SnapshotStore(ThreadedGameSnapshot initialSnapshot) {
        super(initialSnapshot);
    }
}
