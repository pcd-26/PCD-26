package pcd.shas.keypad;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import pcd.shas.common.MySerializable;
import pcd.shas.controlunit.ControlUnitActor;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Typed keypad actor that collects local PIN input and forwards submissions to all discovered control units.
 */
public final class KeypadActor extends AbstractBehavior<KeypadActor.Command> {

    /**
     * Root protocol for the keypad.
     */
    public interface Command extends MySerializable {}

    /**
     * Simulates pressing a single keypad key.
     *
     * @param key the character of the key pressed
     */
    public record PressKey(char key) implements Command {}

    /**
     * Simulates direct submission of a PIN.
     *
     * @param pin the PIN string to submit
     */
    public record SubmitPin(String pin) implements Command {
        public SubmitPin {
            Objects.requireNonNull(pin, "pin");
        }
    }

    // Internal receptionist notification adapter command
    private record ControlUnitsUpdated(Set<ActorRef<ControlUnitActor.Command>> controlUnits) implements Command {}

    private final Set<ActorRef<ControlUnitActor.Command>> controlUnits = new HashSet<>();
    private final StringBuilder pinBuffer = new StringBuilder();

    /**
     * Creates a keypad actor that dynamically discovers the control units via receptionist.
     *
     * @return the keypad behavior
     */
    public static Behavior<Command> create() {
        return Behaviors.setup(KeypadActor::new);
    }

    private KeypadActor(ActorContext<Command> context) {
        super(context);
        // Subscribe to control unit updates from receptionist
        ActorRef<Receptionist.Listing> listingAdapter = context.messageAdapter(
                Receptionist.Listing.class,
                listing -> new ControlUnitsUpdated(listing.getServiceInstances(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY))
        );
        context.getSystem().receptionist().tell(
                Receptionist.subscribe(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY, listingAdapter)
        );
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(ControlUnitsUpdated.class, this::onControlUnitsUpdated)
                .onMessage(PressKey.class, this::onPressKey)
                .onMessage(SubmitPin.class, this::onSubmitPin)
                .build();
    }

    private Behavior<Command> onControlUnitsUpdated(ControlUnitsUpdated command) {
        getContext().getLog().info("Keypad discovered control units: {}", command.controlUnits());
        this.controlUnits.clear();
        this.controlUnits.addAll(command.controlUnits());
        return this;
    }

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

    private Behavior<Command> onSubmitPin(SubmitPin command) {
        submitPinToControlUnits(command.pin());
        return this;
    }

    private void submitBufferedPin() {
        if (pinBuffer.isEmpty()) {
            return;
        }

        submitPinToControlUnits(pinBuffer.toString());
        pinBuffer.setLength(0);
    }

    private void submitPinToControlUnits(String pin) {
        if (controlUnits.isEmpty()) {
            getContext().getLog().warn("No control unit found in the cluster to submit PIN: {}", pin);
            return;
        }
        for (ActorRef<ControlUnitActor.Command> cu : controlUnits) {
            cu.tell(new ControlUnitActor.PinSubmitted(pin));
        }
    }
}
