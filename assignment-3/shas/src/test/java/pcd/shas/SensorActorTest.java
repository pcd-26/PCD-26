package pcd.shas;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.sensor.SensorActor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SensorActorTest {

    private ActorTestKit testKit;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create();
    }

    @AfterEach
    void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    void motionSensorForwardsActivationToControlUnit() {
        TestProbe<ControlUnitActor.Command> controlUnitProbe = testKit.createTestProbe(ControlUnitActor.Command.class);
        var sensor = testKit.spawn(SensorActor.create("living_room_motion", SensorType.MOTION, "Living Area", controlUnitProbe.getRef()));

        sensor.tell(new SensorActor.Activate());

        ControlUnitActor.SensorActivated message = (ControlUnitActor.SensorActivated) controlUnitProbe.receiveMessage();
        assertEquals(new SensorInfo("living_room_motion", SensorType.MOTION, "Living Area"), message.sensorInfo());
        assertNotNull(message.sensorInfo());
    }

    @Test
    void doorWindowSensorForwardsActivationToControlUnit() {
        TestProbe<ControlUnitActor.Command> controlUnitProbe = testKit.createTestProbe(ControlUnitActor.Command.class);
        var sensor = testKit.spawn(SensorActor.create("front_door", SensorType.DOOR_WINDOW, "Perimeter", controlUnitProbe.getRef()));

        sensor.tell(new SensorActor.Activate());

        ControlUnitActor.SensorActivated message = (ControlUnitActor.SensorActivated) controlUnitProbe.receiveMessage();
        assertEquals(new SensorInfo("front_door", SensorType.DOOR_WINDOW, "Perimeter"), message.sensorInfo());
    }
}
