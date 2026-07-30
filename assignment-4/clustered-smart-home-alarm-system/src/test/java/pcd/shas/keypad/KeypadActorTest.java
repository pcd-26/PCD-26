package pcd.shas.keypad;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pcd.shas.controlunit.ControlUnitActor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests for KeypadActor.
 */
public class KeypadActorTest {

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
    public void submitPinIsForwardedToControlUnit() {
        assertForwardedPin(new KeypadActor.SubmitPin("1234"), "1234");
    }

    @Test
    public void bufferedKeysAreSubmittedAsTypedPin() {
        TestProbe<ControlUnitActor.Command> probe = testKit.createTestProbe(ControlUnitActor.Command.class);
        testKit.system().receptionist().tell(
                Receptionist.register(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY, probe.getRef())
        );

        ActorRef<KeypadActor.Command> keypad = testKit.spawn(KeypadActor.create());
        keypad.tell(new KeypadActor.PressKey('4'));
        keypad.tell(new KeypadActor.PressKey('3'));
        keypad.tell(new KeypadActor.PressKey('2'));
        keypad.tell(new KeypadActor.PressKey('1'));
        keypad.tell(new KeypadActor.PressKey('#'));

        ControlUnitActor.Command command = awaitForwardedCommand(
                probe,
                keypad::tell,
                new KeypadActor.PressKey('4'),
                new KeypadActor.PressKey('3'),
                new KeypadActor.PressKey('2'),
                new KeypadActor.PressKey('1'),
                new KeypadActor.PressKey('#')
        );
        ControlUnitActor.PinSubmitted pin = assertInstanceOf(ControlUnitActor.PinSubmitted.class, command);
        assertEquals("4321", pin.pin());
    }

    private void assertForwardedPin(KeypadActor.Command keypadCommand, String expectedPin) {
        TestProbe<ControlUnitActor.Command> probe = testKit.createTestProbe(ControlUnitActor.Command.class);
        testKit.system().receptionist().tell(
                Receptionist.register(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY, probe.getRef())
        );

        ActorRef<KeypadActor.Command> keypad = testKit.spawn(KeypadActor.create());
        ControlUnitActor.Command command = awaitForwardedCommand(probe, keypad::tell, keypadCommand);
        ControlUnitActor.PinSubmitted pin = assertInstanceOf(ControlUnitActor.PinSubmitted.class, command);
        assertEquals(expectedPin, pin.pin());
    }

    @SafeVarargs
    private final ControlUnitActor.Command awaitForwardedCommand(
            TestProbe<ControlUnitActor.Command> probe,
            java.util.function.Consumer<KeypadActor.Command> trigger,
            KeypadActor.Command... commands
    ) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            for (KeypadActor.Command command : commands) {
                trigger.accept(command);
            }
            try {
                return probe.receiveMessage(java.time.Duration.ofMillis(100));
            } catch (AssertionError ignored) {
                // Keep retrying until receptionist discovery completes.
            }
        }
        fail("Timed out waiting for keypad command to reach the control unit");
        return null;
    }
}
