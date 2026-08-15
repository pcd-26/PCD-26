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

public final class KeypadActor extends AbstractBehavior<KeypadActor.Command> {

    public interface Command {}

    public record PressKey(char key) implements Command {}

    public record SubmitPin(String pin) implements Command {
        public SubmitPin {
            Objects.requireNonNull(pin, "pin");
        }
    }

    public record ArmAll() implements Command {}

    public record ArmPartial(Set<Zone> activeZones) implements Command {
        public ArmPartial {
            Objects.requireNonNull(activeZones, "activeZones");
            if (activeZones.isEmpty()) {
                throw new IllegalArgumentException("activeZones cannot be empty");
            }
            activeZones = Set.copyOf(activeZones);
        }
    }

    private final ActorRef<ControlUnitActor.Command> controlUnit;
    private final StringBuilder typedPin = new StringBuilder();

    // Creates the keypad adapter that translates local input into control-unit messages.
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
            .onMessage(PressKey.class, this::onKeyPressed)
            .onMessage(SubmitPin.class, this::onPinSubmittedDirectly)
            .onMessage(ArmAll.class, this::onFullArmingRequested)
            .onMessage(ArmPartial.class, this::onPartialArmingRequested)
            .build();
    }

    // Digits are buffered locally; '#' submits the PIN; '*' clears the current entry.
    private Behavior<Command> onKeyPressed(PressKey command) {
        char pressedKey = command.key();
        if (Character.isDigit(pressedKey)) {
            typedPin.append(pressedKey);
            return this;
        }

        if (pressedKey == '#') {
            submitTypedPin();
            return this;
        }

        if (pressedKey == '*') {
            typedPin.setLength(0);
        }

        return this;
    }

    // Direct submissions are useful for tests and scripted scenarios.
    private Behavior<Command> onPinSubmittedDirectly(SubmitPin command) {
        getContext().getLog().info("Keypad submitting PIN event for pin length={}", command.pin().length());
        controlUnit.tell(new ControlUnitActor.PinSubmitted(command.pin()));
        return this;
    }

    private Behavior<Command> onFullArmingRequested(ArmAll command) {
        getContext().getLog().info("Keypad forwarding full-arming request");
        controlUnit.tell(new ControlUnitActor.ArmAll());
        return this;
    }

    private Behavior<Command> onPartialArmingRequested(ArmPartial command) {
        getContext().getLog().info("Keypad forwarding partial-arming request for zones {}", command.activeZones());
        controlUnit.tell(new ControlUnitActor.ArmPartial(command.activeZones()));
        return this;
    }

    private void submitTypedPin() {
        if (typedPin.isEmpty()) {
            return;
        }

        String pin = typedPin.toString();
        getContext().getLog().info("Keypad submitting buffered PIN event for pin length={}", pin.length());
        controlUnit.tell(new ControlUnitActor.PinSubmitted(pin));
        typedPin.setLength(0);
    }
}
