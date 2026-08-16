package pcd.shas;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import pcd.shas.common.Zone;

import java.time.Duration;
import java.util.Set;

public final class DemoMain {

    private static final String SYSTEM_NAME = "shas-demo";
    private static final Duration DEMO_EXIT_DELAY = Duration.ofMillis(300);
    private static final Duration DEMO_ENTRY_DELAY = Duration.ofMillis(300);
    private static final Duration STEP_GAP = Duration.ofMillis(150);
    private static final Duration FINAL_GRACE = Duration.ofMillis(300);

    private DemoMain() {}

    public static void main(String[] args) {
        AlarmConfiguration loadedConfiguration = AlarmConfiguration.from(ConfigFactory.load());
        AlarmConfiguration demoConfiguration = fastDemoConfiguration(loadedConfiguration);
        ActorSystem<RootActor.Command> system = ActorSystem.create(RootActor.create(demoConfiguration), SYSTEM_NAME);

        try {
            runDemo(system, demoConfiguration);
        } finally {
            system.tell(new RootActor.Stop());
            system.getWhenTerminated().toCompletableFuture().join();
        }
    }

    private static AlarmConfiguration fastDemoConfiguration(AlarmConfiguration alarmConfiguration) {
        return new AlarmConfiguration(alarmConfiguration.correctPin(), DEMO_EXIT_DELAY, DEMO_ENTRY_DELAY);
    }

    static void runDemo(ActorSystem<RootActor.Command> system, AlarmConfiguration alarmConfiguration) {
        String pin = alarmConfiguration.correctPin();
        Duration afterExitDelay = alarmConfiguration.exitDelay().plus(STEP_GAP);
        Duration afterEntryDelay = alarmConfiguration.entryDelay().plus(STEP_GAP);

        system.log().info("Demo step: system starts in RECOVERY");
        system.tell(new RootActor.SubmitPin(pin));
        sleep(STEP_GAP);

        system.log().info("Demo step: full arming is requested with the correct PIN");
        system.tell(new RootActor.RequestFullArming(pin));
        sleep(STEP_GAP);

        system.log().info("Demo step: a sensor event during EXIT_DELAY is ignored");
        system.tell(new RootActor.ActivateFrontDoor());
        sleep(afterExitDelay);

        system.log().info("Demo step: after EXIT_DELAY, a perimeter sensor triggers ENTRY_DELAY");
        system.tell(new RootActor.ActivateFrontDoor());
        sleep(afterEntryDelay);

        system.log().info("Demo step: after ENTRY_DELAY, the alarm is active and the correct PIN stops it");
        system.tell(new RootActor.SubmitPin(pin));
        sleep(STEP_GAP);

        system.log().info("Demo step: night mode arms only perimeter and ground floor zones");
        system.tell(new RootActor.RequestPartialArming(pin, Set.of(Zone.PERIMETER, Zone.GROUND_FLOOR)));
        sleep(afterExitDelay);

        system.log().info("Demo step: a living-room sensor in an inactive zone is ignored");
        system.tell(new RootActor.ActivateLivingRoom());
        sleep(STEP_GAP);

        system.log().info("Demo step: a bedroom sensor in an inactive zone is ignored");
        system.tell(new RootActor.ActivateBedroom());
        sleep(STEP_GAP);

        system.log().info("Demo step: a perimeter sensor in an active zone triggers ENTRY_DELAY");
        system.tell(new RootActor.ActivateFrontDoor());
        sleep(STEP_GAP);

        system.log().info("Demo step: the correct PIN disarms the system during ENTRY_DELAY");
        system.tell(new RootActor.SubmitPin(pin));
        sleep(FINAL_GRACE);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Demo interrupted", exception);
        }
    }

}
