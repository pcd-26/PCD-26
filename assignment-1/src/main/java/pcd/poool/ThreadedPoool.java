package pcd.poool;

import pcd.poool.model.physics.config.ThousandBallsBoardConf;
import pcd.poool.threaded.ThreadedGameRunner;

/** Playable platform-thread version of Poool. */
public final class ThreadedPoool {

    private ThreadedPoool() {
    }

    /** Starts the platform-thread application. */
    public static void main(String[] args) {
        PooolApplication.run(
                () -> new ThreadedGameRunner(new ThousandBallsBoardConf()),
                "poool-threaded-shutdown");
    }
}
