package pcd.shas;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class MainTest {

    @Test
    void mainStartsAndTerminatesActorSystem() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> Main.main(new String[0]));
    }
}
