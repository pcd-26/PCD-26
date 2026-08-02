package pcd.shas;

import org.apache.pekko.actor.typed.ActorSystem;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class MainTest {

    @Test
    void mainStartsAndTerminatesActorSystem() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> Main.main(new String[0]));
    }

    @Test
    void rootBehaviorCompletesTheDemoScenario() {
        var configuration = new AlarmConfiguration("1234", Duration.ofMillis(50), Duration.ofMillis(50));
        ActorSystem<Void> system = ActorSystem.create(Main.createRootBehavior(configuration), "main-test");

        try {
            assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> system.getWhenTerminated().toCompletableFuture().join()
            );
        } finally {
            system.terminate();
            system.getWhenTerminated().toCompletableFuture().join();
        }
    }
}
