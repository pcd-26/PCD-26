package pcd.shas.sensor;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for SensorActor.
 */
public class SensorActorTest {

    private static final java.time.Duration TIMEOUT = java.time.Duration.ofSeconds(1);
    private static ActorTestKit testKit;

    @BeforeAll
    public static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    public static void teardown() {
        testKit.shutdownTestKit();
    }

    @Test
    public void motionSensorPreservesIdentityAndTypeInDeliveredEvent() {
        assertDeliveredEvent("motion-1", SensorType.MOTION, Zone.LIVING_AREA);
    }

    @Test
    public void doorWindowSensorPreservesIdentityAndTypeInDeliveredEvent() {
        assertDeliveredEvent("door-7", SensorType.DOOR_WINDOW, Zone.PERIMETER);
    }

    private void assertDeliveredEvent(String sensorId, SensorType sensorType, Zone zone) {
        TestProbe<ControlUnitActor.Command> probe = testKit.createTestProbe(ControlUnitActor.Command.class);
        testKit.system().receptionist().tell(
                Receptionist.register(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY, probe.getRef())
        );

        ActorRef<SensorActor.Command> sensor = testKit.spawn(
                SensorActor.create(sensorId, sensorType, zone)
        );

        ControlUnitActor.Command command = awaitForwardedCommand(probe, sensor::tell, new SensorActor.Activate());
        ControlUnitActor.SensorActivated event = assertInstanceOf(ControlUnitActor.SensorActivated.class, command);
        assertEquals(sensorId, event.sensorInfo().id());
        assertEquals(sensorType, event.sensorInfo().type());
        assertEquals(zone, event.sensorInfo().zone());
    }

    private ControlUnitActor.Command awaitForwardedCommand(
            TestProbe<ControlUnitActor.Command> probe,
            java.util.function.Consumer<SensorActor.Command> trigger,
            SensorActor.Command command
    ) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            trigger.accept(command);
            try {
                return probe.receiveMessage(java.time.Duration.ofMillis(100));
            } catch (AssertionError ignored) {
                // Keep retrying until receptionist discovery completes.
            }
        }
        fail("Timed out waiting for sensor command to reach the control unit");
        return null;
    }
}
