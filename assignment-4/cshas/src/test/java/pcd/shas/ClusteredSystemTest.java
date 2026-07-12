package pcd.shas;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.keypad.KeypadActor;
import pcd.shas.sensor.SensorActor;
import pcd.shas.siren.SirenActor;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Clustered integration test. Spawns 3 separate local ActorSystems forming a cluster
 * and verifies that keypads, sensors, and the control unit communicate properly
 * via location-transparent receptionist-based messaging.
 */
public class ClusteredSystemTest {

    private static ActorSystem<ControlUnitActor.Command> cuSystem;
    private static ActorSystem<KeypadActor.Command> keypadSystem;
    private static ActorSystem<SensorActor.Command> sensorSystem;

    @BeforeAll
    public static void setupCluster() throws Exception {
        // Use a test config with different ports to avoid collisions
        Config testConfig = ConfigFactory.parseString(
                """
                pekko.cluster.seed-nodes = [
                  "pekko://shas-test-cluster@127.0.0.1:2561"
                ]
                pekko.cluster.seed-node-timeout = 1s
                pekko.cluster.retry-unsuccessful-join-after = 1s
                """
        ).withFallback(ConfigFactory.load());

        Config config1 = ConfigFactory.parseString("pekko.remote.artery.canonical.port = 2561")
                .withFallback(testConfig);
        cuSystem = ActorSystem.create(
                Behaviors.setup(context -> {
                    context.spawn(SirenActor.create(), "siren");
                    ActorRef<ControlUnitActor.Command> cu = context.spawn(
                            ControlUnitActor.create(
                                    "1234",
                                    Duration.ofMillis(500),
                                    Duration.ofMillis(500)
                            ),
                            "control-unit"
                    );
                    return Behaviors.receive(ControlUnitActor.Command.class)
                            .onMessage(ControlUnitActor.Command.class, msg -> {
                                cu.tell(msg);
                                return Behaviors.same();
                            })
                            .build();
                }),
                "shas-test-cluster",
                config1
        );

        // Wait for first seed node (ControlUnit node) to form the cluster
        Thread.sleep(2000);

        Config config2 = ConfigFactory.parseString("pekko.remote.artery.canonical.port = 2562")
                .withFallback(testConfig);
        keypadSystem = ActorSystem.create(
                KeypadActor.create(),
                "shas-test-cluster",
                config2
        );

        Config config3 = ConfigFactory.parseString("pekko.remote.artery.canonical.port = 2563")
                .withFallback(testConfig);
        sensorSystem = ActorSystem.create(
                SensorActor.create("door", SensorType.DOOR_WINDOW, Zone.PERIMETER),
                "shas-test-cluster",
                config3
        );

        // Allow some time for other nodes to join and receptionist synchronization
        Thread.sleep(2500);
    }

    @AfterAll
    public static void teardownCluster() {
        if (cuSystem != null) cuSystem.terminate();
        if (keypadSystem != null) keypadSystem.terminate();
        if (sensorSystem != null) sensorSystem.terminate();
    }

    @Test
    public void testClusteredWorkflow() throws Exception {
        // Query initial state: should be RECOVERY
        AlarmState state = queryState();
        assertEquals(AlarmState.RECOVERY, state);

        // Submit PIN through keypad node (different JVM/ActorSystem in theory)
        keypadSystem.tell(new KeypadActor.SubmitPin("1234"));
        Thread.sleep(1000);

        // Should be DISARMED
        state = queryState();
        assertEquals(AlarmState.DISARMED, state);

        // Configure full arming and arm it
        cuSystem.tell(new ControlUnitActor.ArmAll());
        keypadSystem.tell(new KeypadActor.SubmitPin("1234"));
        Thread.sleep(200);

        // Should be in EXIT_DELAY
        state = queryState();
        assertEquals(AlarmState.EXIT_DELAY, state);

        // Wait for exit delay to expire
        Thread.sleep(800);

        // Should be ARMED
        state = queryState();
        assertEquals(AlarmState.ARMED, state);

        // Trigger sensor on sensor node
        sensorSystem.tell(new SensorActor.Activate());
        Thread.sleep(200);

        // Should be in ENTRY_DELAY
        state = queryState();
        assertEquals(AlarmState.ENTRY_DELAY, state);

        // Disarm again
        keypadSystem.tell(new KeypadActor.SubmitPin("1234"));
        Thread.sleep(500);

        state = queryState();
        assertEquals(AlarmState.DISARMED, state);
    }

    private AlarmState queryState() throws Exception {
        CompletionStage<ControlUnitActor.StateSnapshot> stage = AskPattern.ask(
                cuSystem,
                ControlUnitActor.QueryState::new,
                Duration.ofSeconds(2),
                cuSystem.scheduler()
        );
        ControlUnitActor.StateSnapshot res = stage.toCompletableFuture().get();
        assertNotNull(res);
        return res.state();
    }
}
