package pcd.assignment3.shas.keypad;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import pcd.assignment3.shas.controlunit.ControlUnitActor;

import java.util.HashSet;
import java.util.Set;

/**
 * An actor representing the user keypad for arming, disarming, and stopping the alarm.
 * It accumulates keystrokes, manages zone selection, and forwards submitted PINs to the Control Unit.
 */
public class KeypadActor extends AbstractBehavior<KeypadActor.Command> {

    /**
     * Interface for all commands accepted by the KeypadActor.
     */
    public interface Command {}

    /**
     * Simulates pressing a single character key on the keypad.
     * Use digits '0'-'9', '#' to submit PIN, and '*' to clear the PIN buffer.
     */
    public record PressKey(char key) implements Command {}

    /**
     * Simulates directly submitting a full PIN string (useful for testing or direct CLI).
     */
    public record DirectPinSubmit(String pin) implements Command {}

    /**
     * Command to select a zone for partial arming.
     */
    public record SelectZone(String zone) implements Command {}

    /**
     * Command to deselect a zone.
     */
    public record DeselectZone(String zone) implements Command {}

    /**
     * Command to clear all zone selections.
     */
    public record ClearZoneSelection() implements Command {}

    /**
     * Response from the Control Unit indicating the PIN was correct and accepted.
     */
    public record PinAccepted() implements Command {}

    /**
     * Response from the Control Unit indicating the PIN was incorrect.
     */
    public record PinRejected() implements Command {}

    private final ActorRef<ControlUnitActor.Command> controlUnit;
    private final StringBuilder pinBuffer = new StringBuilder();
    private final Set<String> selectedZones = new HashSet<>();

    /**
     * Factory method to create a KeypadActor behavior.
     *
     * @param controlUnit the central control unit actor reference
     * @return the behavior of the KeypadActor
     */
    public static Behavior<Command> create(ActorRef<ControlUnitActor.Command> controlUnit) {
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
                .onMessage(DirectPinSubmit.class, this::onDirectPinSubmit)
                .onMessage(SelectZone.class, this::onSelectZone)
                .onMessage(DeselectZone.class, this::onDeselectZone)
                .onMessage(ClearZoneSelection.class, this::onClearZoneSelection)
                .onMessage(PinAccepted.class, this::onPinAccepted)
                .onMessage(PinRejected.class, this::onPinRejected)
                .build();
    }

    private Behavior<Command> onPressKey(PressKey cmd) {
        char key = cmd.key();
        if (key == '#') {
            submitPin();
        } else if (key == '*') {
            pinBuffer.setLength(0);
            getContext().getLog().info("Keypad buffer cleared.");
            System.out.println("Keypad: [Buffer cleared]");
        } else if (Character.isDigit(key)) {
            pinBuffer.append(key);
            String masked = "*".repeat(pinBuffer.length());
            getContext().getLog().info("Keypad key pressed: {}, current: {}", key, masked);
            System.out.println("Keypad: " + masked);
        } else {
            getContext().getLog().warn("Invalid key pressed on keypad: {}", key);
        }
        return this;
    }

    private Behavior<Command> onDirectPinSubmit(DirectPinSubmit cmd) {
        pinBuffer.setLength(0);
        pinBuffer.append(cmd.pin());
        submitPin();
        return this;
    }

    private Behavior<Command> onSelectZone(SelectZone cmd) {
        selectedZones.add(cmd.zone());
        getContext().getLog().info("Zone selected: {}", cmd.zone());
        System.out.println("Keypad: Selected zone '" + cmd.zone() + "'. Active selections: " + selectedZones);
        return this;
    }

    private Behavior<Command> onDeselectZone(DeselectZone cmd) {
        selectedZones.remove(cmd.zone());
        getContext().getLog().info("Zone deselected: {}", cmd.zone());
        System.out.println("Keypad: Deselected zone '" + cmd.zone() + "'. Active selections: " + selectedZones);
        return this;
    }

    private Behavior<Command> onClearZoneSelection(ClearZoneSelection cmd) {
        selectedZones.clear();
        getContext().getLog().info("Zone selection cleared.");
        System.out.println("Keypad: Zone selections cleared (will arm all zones).");
        return this;
    }

    private Behavior<Command> onPinAccepted(PinAccepted cmd) {
        getContext().getLog().info("PIN verification succeeded!");
        System.out.println("\nKeypad DISPLAY: [PIN ACCEPTED]\n");
        return this;
    }

    private Behavior<Command> onPinRejected(PinRejected cmd) {
        getContext().getLog().warn("PIN verification failed!");
        System.out.println("\nKeypad DISPLAY: [ACCESS DENIED - INCORRECT PIN]\n");
        return this;
    }

    private void submitPin() {
        String enteredPin = pinBuffer.toString();
        pinBuffer.setLength(0); // clear the buffer after submitting
        if (enteredPin.isEmpty()) {
            System.out.println("Keypad DISPLAY: [NO PIN ENTERED]");
            return;
        }
        getContext().getLog().info("Submitting PIN code to control unit...");
        // Pass a copy of the selected zones
        Set<String> zonesToArm = new HashSet<>(selectedZones);
        controlUnit.tell(new ControlUnitActor.KeypadPinEntered(enteredPin, zonesToArm, getContext().getSelf()));
    }
}
