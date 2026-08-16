package pcd.shas;

import com.typesafe.config.Config;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.Cluster;
import org.apache.pekko.serialization.Serialization;
import org.apache.pekko.serialization.SerializationExtension;
import org.apache.pekko.serialization.Serializer;
import org.apache.pekko.serialization.SerializerWithStringManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.keypad.KeypadActor;
import pcd.shas.runtime.NodeStartup;
import pcd.shas.sensor.SensorActor;
import pcd.shas.siren.SirenActor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for the clustered SHAS deployment.
 */
public class ClusteredSystemTest {

    private static final String SYSTEM_NAME = "shas-test-cluster";
    private static final String HOST = "127.0.0.1";
    private static final List<String> SEED_NODES = List.of(
            "127.0.0.1:2561",
            "127.0.0.1:2562",
            "127.0.0.1:2563"
    );
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private ActorSystem<ControlUnitActor.Command> cuSystem;
    private ActorSystem<KeypadActor.Command> keypadSystem;
    private ActorSystem<SensorActor.Command> sensorSystem;

    @BeforeEach
    public void startCluster() throws Exception {
        Config controlConfig = NodeStartup.buildClusterConfig(SYSTEM_NAME, HOST, 2561, SEED_NODES);
        Config keypadConfig = NodeStartup.buildClusterConfig(SYSTEM_NAME, HOST, 2562, SEED_NODES);
        Config sensorConfig = NodeStartup.buildClusterConfig(SYSTEM_NAME, HOST, 2563, SEED_NODES);

        cuSystem = ActorSystem.create(controlUnitNodeBehavior(), SYSTEM_NAME, controlConfig);
        keypadSystem = ActorSystem.create(KeypadActor.create(), SYSTEM_NAME, keypadConfig);
        sensorSystem = ActorSystem.create(SensorActor.create("door-1", SensorType.DOOR_WINDOW, Zone.PERIMETER), SYSTEM_NAME, sensorConfig);

        awaitClusterSize(3);
        awaitState(cuSystem, AlarmState.RECOVERY);
    }

    @AfterEach
    public void stopCluster() {
        shutdown(cuSystem);
        shutdown(keypadSystem);
        shutdown(sensorSystem);
    }

    @Test
    public void createsAndFormsAThreeNodeCluster() {
        assertEquals(2561, cuSystem.settings().config().getInt("pekko.remote.artery.canonical.port"));
        assertEquals(2562, keypadSystem.settings().config().getInt("pekko.remote.artery.canonical.port"));
        assertEquals(2563, sensorSystem.settings().config().getInt("pekko.remote.artery.canonical.port"));
        assertEquals(3, clusterMemberCount(cuSystem));
    }

    @Test
    public void controlUnitKeypadAndSensorRunOnDistinctNodesAndDiscoverEachOther() throws Exception {
        eventuallySubmitPin("1234", AlarmState.DISARMED);

        keypadSystem.tell(new KeypadActor.RequestFullArming("1234"));
        awaitState(cuSystem, AlarmState.EXIT_DELAY);
        awaitState(cuSystem, AlarmState.ARMED);

        sensorSystem.tell(new SensorActor.Activate());
        awaitState(cuSystem, AlarmState.ENTRY_DELAY);

        keypadSystem.tell(new KeypadActor.SubmitPin("1234"));
        awaitState(cuSystem, AlarmState.DISARMED);
    }

    @Test
    public void actorMessagesRoundTripThroughConfiguredSerializer() throws Exception {
        Serialization serialization = SerializationExtension.get(cuSystem);

        roundTrip(serialization, new ControlUnitActor.PinSubmitted("1234"));
        roundTrip(serialization, new ControlUnitActor.RequestFullArming("1234"));
        roundTrip(serialization, new ControlUnitActor.RequestPartialArming("1234", java.util.Set.of(Zone.PERIMETER)));
        roundTrip(serialization, new ControlUnitActor.SensorActivated(
                new pcd.shas.common.SensorInfo("motion-1", SensorType.MOTION, Zone.LIVING_AREA)
        ));
        roundTrip(serialization, new ControlUnitActor.StateSnapshot(AlarmState.RECOVERY));
        roundTrip(serialization, new KeypadActor.PressKey('#'));
        roundTrip(serialization, new KeypadActor.RequestFullArming("1234"));
        roundTrip(serialization, new KeypadActor.RequestPartialArming("1234", java.util.Set.of(Zone.PERIMETER)));
        roundTrip(serialization, new SensorActor.Activate());
    }

    @Test
    public void remoteControlUnitRecreationReturnsToRecovery() throws Exception {
        eventuallySubmitPin("1234", AlarmState.DISARMED);

        cuSystem.tell(new ControlUnitActor.RequestFullArming("1234"));
        awaitState(cuSystem, AlarmState.EXIT_DELAY);
        awaitState(cuSystem, AlarmState.ARMED);

        cuSystem.terminate();
        cuSystem.getWhenTerminated().toCompletableFuture().get(2, TimeUnit.SECONDS);

        Config controlConfig = NodeStartup.buildClusterConfig(SYSTEM_NAME, HOST, 2561, SEED_NODES);
        cuSystem = ActorSystem.create(controlUnitNodeBehavior(), SYSTEM_NAME, controlConfig);
        awaitState(cuSystem, AlarmState.RECOVERY, Duration.ofSeconds(15));
    }

    private void eventuallySubmitPin(String pin, AlarmState expectedState) throws Exception {
        eventuallySubmitPin(pin, expectedState, TIMEOUT);
    }

    private void eventuallySubmitPin(String pin, AlarmState expectedState, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        AlarmState current = null;
        while (System.nanoTime() < deadline) {
            keypadSystem.tell(new KeypadActor.SubmitPin(pin));
            TimeUnit.MILLISECONDS.sleep(150);
            try {
                current = queryState(cuSystem);
                if (current == expectedState) {
                    return;
                }
            } catch (AssertionError ignored) {
                // Keep retrying until the remote control unit is reachable.
            }
        }
        throw new AssertionError("Expected state " + expectedState + " but found " + current);
    }

    private Behavior<ControlUnitActor.Command> controlUnitNodeBehavior() {
        return Behaviors.setup(context -> {
            context.spawn(SirenActor.create(), "siren");
            ActorRef<ControlUnitActor.Command> controlUnit = context.spawn(
                    ControlUnitActor.create("1234", Duration.ofMillis(80), Duration.ofMillis(80)),
                    "control-unit"
            );
            return Behaviors.receive(ControlUnitActor.Command.class)
                    .onMessage(ControlUnitActor.Command.class, message -> {
                        controlUnit.tell(message);
                        return Behaviors.same();
                    })
                    .build();
        });
    }

    private void awaitState(ActorSystem<ControlUnitActor.Command> system, AlarmState expected) throws Exception {
        awaitState(system, expected, TIMEOUT);
    }

    private void awaitState(ActorSystem<ControlUnitActor.Command> system, AlarmState expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        AlarmState current = null;
        while (System.nanoTime() < deadline) {
            current = queryState(system);
            if (current == expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("Expected state " + expected + " but found " + current);
    }

    private void awaitClusterSize(int expectedSize) throws Exception {
        awaitClusterSize(expectedSize, TIMEOUT);
    }

    private void awaitClusterSize(int expectedSize, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        int current = 0;
        while (System.nanoTime() < deadline) {
            current = clusterMemberCount(cuSystem);
            if (current == expectedSize) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("Expected cluster size " + expectedSize + " but found " + current);
    }

    private AlarmState queryState(ActorSystem<ControlUnitActor.Command> system) throws Exception {
        CompletionStage<ControlUnitActor.StateSnapshot> stage = AskPattern.ask(
                system,
                ControlUnitActor.QueryState::new,
                Duration.ofSeconds(1),
                system.scheduler()
        );
        ControlUnitActor.StateSnapshot snapshot = stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertNotNull(snapshot);
        return snapshot.state();
    }

    private int clusterMemberCount(ActorSystem<?> system) {
        return Cluster.get(system).state().members().iterator().size();
    }

    private void roundTrip(Serialization serialization, Object message) throws Exception {
        Serializer serializer = serialization.findSerializerFor(message);
        assertInstanceOf(SerializerWithStringManifest.class, serializer);
        SerializerWithStringManifest manifestSerializer = (SerializerWithStringManifest) serializer;

        byte[] bytes = manifestSerializer.toBinary(message);
        Object decoded = manifestSerializer.fromBinary(bytes, manifestSerializer.manifest(message));

        assertEquals(message, decoded);
    }

    private void shutdown(ActorSystem<?> system) {
        if (system != null) {
            system.terminate();
            system.getWhenTerminated().toCompletableFuture().join();
        }
    }
}
