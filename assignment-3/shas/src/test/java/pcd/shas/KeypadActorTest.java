package pcd.shas;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.keypad.KeypadActor;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeypadActorTest {

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
    void keySequenceIsForwardedAsPinSubmission() {
        TestProbe<ControlUnitActor.Command> controlUnitProbe = testKit.createTestProbe(ControlUnitActor.Command.class);
        var keypad = testKit.spawn(KeypadActor.create(controlUnitProbe.getRef()));

        keypad.tell(new KeypadActor.PressKey('1'));
        keypad.tell(new KeypadActor.PressKey('2'));
        keypad.tell(new KeypadActor.PressKey('3'));
        keypad.tell(new KeypadActor.PressKey('4'));
        keypad.tell(new KeypadActor.PressKey('#'));

        ControlUnitActor.PinSubmitted message = (ControlUnitActor.PinSubmitted) controlUnitProbe.receiveMessage();
        assertEquals("1234", message.pin());
    }

    @Test
    void directPinSubmissionIsForwardedWithoutValidation() {
        TestProbe<ControlUnitActor.Command> controlUnitProbe = testKit.createTestProbe(ControlUnitActor.Command.class);
        var keypad = testKit.spawn(KeypadActor.create(controlUnitProbe.getRef()));

        keypad.tell(new KeypadActor.SubmitPin("not-a-pin"));

        ControlUnitActor.PinSubmitted message = (ControlUnitActor.PinSubmitted) controlUnitProbe.receiveMessage();
        assertEquals("not-a-pin", message.pin());
    }
}
