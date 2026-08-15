package pcd.shas;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.Terminated;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import pcd.shas.demo.DemoScenarioActor;

import java.time.Duration;
import java.util.Objects;

public final class DemoMain {

    private static final String SYSTEM_NAME = "shas-demo";
    private static final Duration DEMO_EXIT_DELAY = Duration.ofMillis(300);
    private static final Duration DEMO_ENTRY_DELAY = Duration.ofMillis(300);

    private DemoMain() {}

    public static void main(String[] args) {
        AlarmConfiguration alarmConfiguration = AlarmConfiguration.from(ConfigFactory.load());
        ActorSystem<Void> system = ActorSystem.create(createRootBehavior(fastDemoConfiguration(alarmConfiguration)), SYSTEM_NAME);
        system.getWhenTerminated().toCompletableFuture().join();
    }

    private static AlarmConfiguration fastDemoConfiguration(AlarmConfiguration alarmConfiguration) {
        return new AlarmConfiguration(alarmConfiguration.correctPin(), DEMO_EXIT_DELAY, DEMO_ENTRY_DELAY);
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
