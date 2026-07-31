package pcd.shas.keypad;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import pcd.shas.controlunit.ControlUnitActor;

import java.util.Objects;
import java.util.Set;

/**
 * Typed keypad actor that collects local PIN input and forwards submissions to the control unit.
 */
public final class KeypadActor extends AbstractBehavior<KeypadActor.Command> {

    /**
     * Root protocol for the keypad.
     */
    public interface Command {}

    /**
     * Simulates pressing a single keypad key.
     *
     * @param key character key pressed (0-9 for digits, '#' for submit, '*' for clear)
     */
    public record PressKey(char key) implements Command {}

    /**
     * Simulates direct submission of a PIN.
     *
     * @param pin the submitted PIN string
     */
    public record SubmitPin(String pin) implements Command {
        /**
         * Compact constructor validating that the submitted PIN string is non-null.
         *
         * @throws NullPointerException if {@code pin} is null
         */
        public SubmitPin {
            Objects.requireNonNull(pin, "pin");
        }
    }

    private final ActorRef<ControlUnitActor.Command> controlUnit;
    private final StringBuilder pinBuffer = new StringBuilder();

    /**
     * Creates a keypad actor that forwards submissions to the provided control unit.
     *
     * @param controlUnit the control unit actor
     * @return the keypad behavior
     * @throws NullPointerException if {@code controlUnit} is null
     */
    public static Behavior<Command> create(ActorRef<ControlUnitActor.Command> controlUnit) {
        Objects.requireNonNull(controlUnit, "controlUnit");
        return Behaviors.setup(context -> new KeypadActor(context, controlUnit));
    }

    private KeypadActor(ActorContext<Command> context, ActorRef<ControlUnitActor.Command> controlUnit) {
        super(context);
        this.controlUnit = controlUnit;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(PressKey.class, this::onPressKey)
            .onMessage(SubmitPin.class, this::onSubmitPin)
            .build();
    }

    /**
     * Handles single keypress commands, buffering digits, submitting on '#', or clearing buffer on '*'.
     *
     * @param command keypress command
     * @return behavior instance
     */
    private Behavior<Command> onPressKey(PressKey command) {
        char key = command.key();
        if (Character.isDigit(key)) {
            pinBuffer.append(key);
            return this;
        }

        if (key == '#') {
            submitBufferedPin();
            return this;
        }

        if (key == '*') {
            pinBuffer.setLength(0);
        }

        return this;
    }

    /**
     * Handles direct PIN submission commands.
     *
     * @param command PIN submission command
     * @return behavior instance
     */
    private Behavior<Command> onSubmitPin(SubmitPin command) {
        PinSubmitted event = new PinSubmitted(command.pin(), Set.of(), getContext().getSelf());
        getContext().getLog().info("Keypad submitting PIN event for pin length={}", event.pin().length());
        controlUnit.tell(new ControlUnitActor.PinSubmitted(event.pin()));
        return this;
    }

    /**
     * Submits buffered digit sequence to the control unit and resets buffer.
     */
    private void submitBufferedPin() {
        if (pinBuffer.isEmpty()) {
            return;
        }

        PinSubmitted event = new PinSubmitted(pinBuffer.toString(), Set.of(), getContext().getSelf());
        getContext().getLog().info("Keypad submitting buffered PIN event for pin length={}", event.pin().length());
        controlUnit.tell(new ControlUnitActor.PinSubmitted(event.pin()));
        pinBuffer.setLength(0);
    }
}
