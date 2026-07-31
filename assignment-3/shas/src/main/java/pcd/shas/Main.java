package pcd.shas;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.Terminated;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import pcd.shas.demo.DemoScenarioActor;

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
    private static final Config DEMO_OVERRIDE = ConfigFactory.parseString(
        """
        shas {
          correctPin = "1234"
          exitDelay = 300 milliseconds
          entryDelay = 300 milliseconds
        }
        """
    );

    private Main() {}   // Utility class

    public static void main(String[] args) {
        Config config = DEMO_OVERRIDE.withFallback(ConfigFactory.load()).resolve();
        AlarmConfiguration configuration = AlarmConfiguration.from(config);
        ActorSystem<Void> system = ActorSystem.create(createRootBehavior(configuration), SYSTEM_NAME);
        system.getWhenTerminated().toCompletableFuture().join();
    }

    static Behavior<Void> createRootBehavior(AlarmConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");

        return Behaviors.setup(context -> {
            var demo = context.spawn(DemoScenarioActor.create(configuration), "demo-scenario");
            context.watch(demo);
            demo.tell(new DemoScenarioActor.Start());
            return Behaviors.receiveSignal((signalContext, signal) -> {
                if (signal instanceof Terminated) {
                    return Behaviors.stopped();
                }
                return Behaviors.same();
            });
        });
    }
}
