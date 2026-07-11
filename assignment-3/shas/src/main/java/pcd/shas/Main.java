package pcd.shas;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * Minimal entry point for the Assignment 3 Pekko project.
 *
 * <p>The application currently only verifies that the actor system can be created
 * and shut down cleanly. Alarm logic will be added in later tasks.</p>
 */
public final class Main {

    private static final String SYSTEM_NAME = "shas";

    private Main() {
        // Utility class.
    }

    public static void main(String[] args) {
        ActorSystem<Void> system = ActorSystem.create(createRootBehavior(), SYSTEM_NAME);
        system.terminate();
        system.getWhenTerminated().toCompletableFuture().join();
    }

    static Behavior<Void> createRootBehavior() {
        return Behaviors.empty();
    }
}
