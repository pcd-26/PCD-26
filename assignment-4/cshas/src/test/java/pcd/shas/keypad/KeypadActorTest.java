package pcd.shas.keypad;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pcd.shas.controlunit.ControlUnitActor;

/**
 * Unit tests for KeypadActor.
 */
public class KeypadActorTest {

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
    public void testPinSubmissionToControlUnit() throws Exception {
        TestProbe<ControlUnitActor.Command> probe = testKit.createTestProbe(ControlUnitActor.Command.class);
        
        // Register the probe with the receptionist as a control unit
        testKit.system().receptionist().tell(
                Receptionist.register(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY, probe.getRef())
        );

        ActorRef<KeypadActor.Command> keypad = testKit.spawn(KeypadActor.create());

        // Wait a bit for receptionist propagation
        Thread.sleep(500);

        // Submit a pin directly
        keypad.tell(new KeypadActor.SubmitPin("1234"));

        // Verify the control unit received PinSubmitted
        probe.expectMessage(new ControlUnitActor.PinSubmitted("1234"));
    }

    @Test
    public void testBufferKeysAndSubmit() throws Exception {
        TestProbe<ControlUnitActor.Command> probe = testKit.createTestProbe(ControlUnitActor.Command.class);
        
        // Register the probe
        testKit.system().receptionist().tell(
                Receptionist.register(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY, probe.getRef())
        );

        ActorRef<KeypadActor.Command> keypad = testKit.spawn(KeypadActor.create());
        Thread.sleep(500);

        // Press digits
        keypad.tell(new KeypadActor.PressKey('4'));
        keypad.tell(new KeypadActor.PressKey('3'));
        keypad.tell(new KeypadActor.PressKey('2'));
        keypad.tell(new KeypadActor.PressKey('1'));
        
        // Submit
        keypad.tell(new KeypadActor.PressKey('#'));

        probe.expectMessage(new ControlUnitActor.PinSubmitted("4321"));
    }
}
