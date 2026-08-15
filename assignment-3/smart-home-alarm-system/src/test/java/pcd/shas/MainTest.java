package pcd.shas;

import org.apache.pekko.actor.typed.ActorSystem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class MainTest {

    @Test
    void cliMainTerminatesOnQuit() {
        InputStream originalInput = System.in;
        System.setIn(new ByteArrayInputStream("quit\n".getBytes(StandardCharsets.UTF_8)));

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> Main.main(new String[0]));
        } finally {
            System.setIn(originalInput);
        }
    }

    @Test
    void demoMainScriptCompletes() {
        var configuration = new AlarmConfiguration("1234", Duration.ofMillis(300), Duration.ofMillis(300));
        ActorSystem<RootActor.Command> system = ActorSystem.create(RootActor.create(configuration), "main-test");

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> DemoMain.runDemo(system, configuration));
        } finally {
            system.tell(new RootActor.Stop());
            system.getWhenTerminated().toCompletableFuture().join();
        }
    }
}
