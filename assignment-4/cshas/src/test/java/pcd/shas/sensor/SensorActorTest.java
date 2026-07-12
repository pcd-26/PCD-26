package pcd.shas.sensor;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for SensorActor.
 */
public class SensorActorTest {

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
    public void testSensorActivationForwardsToControlUnit() throws Exception {
        TestProbe<ControlUnitActor.Command> probe = testKit.createTestProbe(ControlUnitActor.Command.class);
        
        // Register the probe with the receptionist
        testKit.system().receptionist().tell(
                Receptionist.register(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY, probe.getRef())
        );

        ActorRef<SensorActor.Command> sensor = testKit.spawn(
                SensorActor.create("s1", SensorType.MOTION, Zone.LIVING_AREA)
        );

        Thread.sleep(500);

        // Activate sensor
        sensor.tell(new SensorActor.Activate());

        // Expect control unit to receive SensorActivated
        ControlUnitActor.Command received = probe.receiveMessage();
        assert(received instanceof ControlUnitActor.SensorActivated);
        ControlUnitActor.SensorActivated event = (ControlUnitActor.SensorActivated) received;
        assertEquals("s1", event.sensorInfo().id());
        assertEquals(SensorType.MOTION, event.sensorInfo().type());
        assertEquals(Zone.LIVING_AREA, event.sensorInfo().zone());
    }
}
