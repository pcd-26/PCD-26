package pcd.shas.keypad;

import org.apache.pekko.actor.typed.ActorRef;
import java.util.Objects;
import java.util.Set;

/**
 * Event emitted by the keypad when a PIN code and selected zones are submitted.
 *
 * @param pin the submitted PIN string
 * @param selectedZones immutable set of selected zone names
 * @param keypadRef reference to the originating keypad actor (optional, may be null)
 */
public record PinSubmitted(String pin, Set<String> selectedZones, ActorRef<KeypadActor.Command> keypadRef) {
    /**
     * Compact constructor validating that pin and selectedZones are non-null and creating a defensive copy of selectedZones.
     *
     * @throws NullPointerException if {@code pin} or {@code selectedZones} is null
     */
    public PinSubmitted {
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(selectedZones, "selectedZones");
        selectedZones = Set.copyOf(selectedZones);
    }
}

