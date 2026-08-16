package pcd.shas.keypad;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import pcd.shas.common.MySerializable;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class KeypadActor extends AbstractBehavior<KeypadActor.Command> {

    // Protocol

    public interface Command extends MySerializable {}

    public record PressKey(char key) implements Command {}

    public record SubmitPin(String pin) implements Command {
        public SubmitPin {
            Objects.requireNonNull(pin, "pin");
        }
    }

    public record RequestFullArming(String pin) implements Command {
        public RequestFullArming {
            Objects.requireNonNull(pin, "pin");
        }
    }

    public record RequestPartialArming(String pin, Set<Zone> activeZones) implements Command {
        public RequestPartialArming {
            Objects.requireNonNull(pin, "pin");
            Objects.requireNonNull(activeZones, "activeZones");
            if (activeZones.isEmpty()) {
                throw new IllegalArgumentException("activeZones cannot be empty");
            }
            activeZones = Set.copyOf(activeZones);
        }
    }

    private record ControlUnitsUpdated(Set<ActorRef<ControlUnitActor.Command>> controlUnits) implements Command {}

    // State

    private Optional<ActorRef<ControlUnitActor.Command>> controlUnit = Optional.empty();
    private final StringBuilder typedPin = new StringBuilder();

    // Creation

    // Creates the keypad adapter that translates local input into control-unit messages.
    public static Behavior<Command> create() {
        return Behaviors.setup(KeypadActor::new);
    }

    // Creates the keypad adapter that translates local input into control-unit messages.
    public static Behavior<Command> create(ActorRef<ControlUnitActor.Command> controlUnit) {
        Objects.requireNonNull(controlUnit, "controlUnit");
        return Behaviors.setup(context -> new KeypadActor(context, controlUnit));
    }

    private KeypadActor(ActorContext<Command> context) {
        super(context);

        ActorRef<Receptionist.Listing> listingAdapter = context.messageAdapter(
            Receptionist.Listing.class,
            listing -> new ControlUnitsUpdated(listing.getServiceInstances(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY))
        );
        context.getSystem().receptionist().tell(
            Receptionist.subscribe(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY, listingAdapter)
        );
    }

    private KeypadActor(ActorContext<Command> context, ActorRef<ControlUnitActor.Command> controlUnit) {
        super(context);
        this.controlUnit = Optional.of(controlUnit);
    }

    // Message handlers

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(ControlUnitsUpdated.class, this::onControlUnitsUpdated)
            // Handles one physical keypad key press.
            .onMessage(PressKey.class, this::onKeyPressed)
            // Handles a complete PIN submitted directly.
            .onMessage(SubmitPin.class, this::onPinSubmittedDirectly)
            // Handles a full-arming request with PIN.
            .onMessage(RequestFullArming.class, this::onFullArmingRequested)
            // Handles a partial-arming request with PIN and zones.
            .onMessage(RequestPartialArming.class, this::onPartialArmingRequested)
            .build();
    }

    private Behavior<Command> onControlUnitsUpdated(ControlUnitsUpdated command) {
        controlUnit = command.controlUnits().stream()
            .max(Comparator.comparing(ref -> ref.path().toString()));
        return this;
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
        getContext().getLog().info("[KEYPAD] PIN submitted. Length={}.", command.pin().length());
        tellControlUnit(new ControlUnitActor.PinSubmitted(command.pin()));
        return this;
    }

    private Behavior<Command> onFullArmingRequested(RequestFullArming command) {
        getContext().getLog().info("[KEYPAD] Full arming requested. PIN length={}.", command.pin().length());
        tellControlUnit(new ControlUnitActor.RequestFullArming(command.pin()));
        return this;
    }

    private Behavior<Command> onPartialArmingRequested(RequestPartialArming command) {
        getContext().getLog().info(
            "[KEYPAD] Partial arming requested. Zones={}, PIN length={}.",
            formatZones(command.activeZones()),
            command.pin().length()
        );
        tellControlUnit(new ControlUnitActor.RequestPartialArming(command.pin(), command.activeZones()));
        return this;
    }

    // Helpers

    private void submitTypedPin() {
        if (typedPin.isEmpty()) {
            return;
        }

        String pin = typedPin.toString();
        getContext().getLog().info("[KEYPAD] Buffered PIN submitted. Length={}.", pin.length());
        tellControlUnit(new ControlUnitActor.PinSubmitted(pin));
        typedPin.setLength(0);
    }

    private void tellControlUnit(ControlUnitActor.Command command) {
        if (controlUnit.isEmpty()) {
            getContext().getLog().warn("[KEYPAD] No control unit found in the cluster.");
            return;
        }
        controlUnit.get().tell(command);
    }

    private static String formatZones(Set<Zone> zones) {
        return zones.stream()
            .sorted()
            .map(Zone::name)
            .collect(Collectors.joining(", ", "[", "]"));
    }
}
