package pcd.shas;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.Terminated;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import pcd.shas.demo.DemoScenarioActor;

import java.util.Objects;

public final class Main {

    private static final String SYSTEM_NAME = "shas";
    private static final Config FAST_DEMO_CONFIG = ConfigFactory.parseString(
        """
        shas {
          correctPin = "1234"
          exitDelay = 300 milliseconds
          entryDelay = 300 milliseconds
        }
        """
    );

    private Main() {}

    public static void main(String[] args) {
        Config config = FAST_DEMO_CONFIG.withFallback(ConfigFactory.load()).resolve();
        AlarmConfiguration alarmConfiguration = AlarmConfiguration.from(config);
        ActorSystem<Void> system = ActorSystem.create(createRootBehavior(alarmConfiguration), SYSTEM_NAME);
        system.getWhenTerminated().toCompletableFuture().join();
    }

    // The guardian starts the scripted demo and shuts the ActorSystem down when it ends.
    static Behavior<Void> createRootBehavior(AlarmConfiguration alarmConfiguration) {
        Objects.requireNonNull(alarmConfiguration, "alarmConfiguration");

        return Behaviors.setup(context -> {
            var demoScenario = context.spawn(DemoScenarioActor.create(alarmConfiguration), "demo-scenario");
            context.watch(demoScenario);
            demoScenario.tell(new DemoScenarioActor.Start());
            return Behaviors.receiveSignal((signalContext, signal) -> {
                if (signal instanceof Terminated) {
                    return Behaviors.stopped();
                }
                return Behaviors.same();
            });
        });
    }
}
