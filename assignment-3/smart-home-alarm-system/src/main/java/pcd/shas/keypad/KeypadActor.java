package pcd.shas.keypad;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;

import java.util.Objects;
import java.util.Set;

/**
 * Typed keypad actor that collects local PIN input and forwards arming-related submissions to the control unit.
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

    /**
     * Requests full arming through the keypad.
     */
    public record ArmAll() implements Command {}

    /**
     * Requests partial arming for a selected set of zones through the keypad.
     */
    public record ArmPartial(Set<Zone> activeZones) implements Command {
        /**
         * Compact constructor validating that the selected zones are non-null and non-empty.
         *
         * @throws NullPointerException if {@code activeZones} is null
         * @throws IllegalArgumentException if {@code activeZones} is empty
         */
        public ArmPartial {
            Objects.requireNonNull(activeZones, "activeZones");
            if (activeZones.isEmpty()) {
                throw new IllegalArgumentException("activeZones cannot be empty");
            }
            activeZones = Set.copyOf(activeZones);
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
            .onMessage(ArmAll.class, this::onArmAll)
            .onMessage(ArmPartial.class, this::onArmPartial)
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
        getContext().getLog().info("Keypad submitting PIN event for pin length={}", command.pin().length());
        controlUnit.tell(new ControlUnitActor.PinSubmitted(command.pin()));
        return this;
    }

    /**
     * Forwards a request to arm all zones through the control unit.
     *
     * @param command full-arming request
     * @return behavior instance
     */
    private Behavior<Command> onArmAll(ArmAll command) {
        getContext().getLog().info("Keypad forwarding full-arming request");
        controlUnit.tell(new ControlUnitActor.ArmAll());
        return this;
    }

    /**
     * Forwards a partial-arming request through the control unit.
     *
     * @param command partial-arming request
     * @return behavior instance
     */
    private Behavior<Command> onArmPartial(ArmPartial command) {
        getContext().getLog().info("Keypad forwarding partial-arming request for zones {}", command.activeZones());
        controlUnit.tell(new ControlUnitActor.ArmPartial(command.activeZones()));
        return this;
    }

    /**
     * Submits buffered digit sequence to the control unit and resets buffer.
     */
    private void submitBufferedPin() {
        if (pinBuffer.isEmpty()) {
            return;
        }

        String pin = pinBuffer.toString();
        getContext().getLog().info("Keypad submitting buffered PIN event for pin length={}", pin.length());
        controlUnit.tell(new ControlUnitActor.PinSubmitted(pin));
        pinBuffer.setLength(0);
    }
}
