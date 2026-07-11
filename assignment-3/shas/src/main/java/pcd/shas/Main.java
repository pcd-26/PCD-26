package pcd.shas;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.siren.SirenActor;

import java.util.Objects;

/**
 * Minimal entry point for the Assignment 3 Pekko project.
 *
 * <p>The application loads the smart-home alarm configuration from the standard
 * Typesafe Config mechanism, wires the core actors, and then shuts down cleanly
 * so the bootstrap remains minimal.</p>
 */
public final class Main {

    private static final String SYSTEM_NAME = "shas";

    private Main() {
        // Utility class.
    }

    public static void main(String[] args) {
        AlarmConfiguration configuration = AlarmConfiguration.from(ConfigFactory.load());
        ActorSystem<Void> system = ActorSystem.create(createRootBehavior(configuration), SYSTEM_NAME);
        system.terminate();
        system.getWhenTerminated().toCompletableFuture().join();
    }

    static Behavior<Void> createRootBehavior(AlarmConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");

        return Behaviors.setup(context -> {
            ActorRef<SirenActor.Command> siren = context.spawn(SirenActor.create(), "siren");
            context.spawn(
                    ControlUnitActor.create(
                            configuration.correctPin(),
                            configuration.exitDelay(),
                            configuration.entryDelay(),
                            siren
                    ),
                    "control-unit"
            );
            return Behaviors.empty();
        });
    }
}
