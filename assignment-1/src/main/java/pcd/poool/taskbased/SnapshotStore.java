package pcd.poool.taskbased;

/**
 * Monitor that stores the latest immutable snapshot published by the
 * simulation controller.
 */
public class SnapshotStore extends pcd.poool.runtime.SnapshotStoreSupport<TaskBasedGameSnapshot> {

    SnapshotStore(TaskBasedGameSnapshot initialSnapshot) {
        super(initialSnapshot);
    }
}
