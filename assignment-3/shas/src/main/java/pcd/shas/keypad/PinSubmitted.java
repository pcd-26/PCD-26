package pcd.shas.keypad;

import org.apache.pekko.actor.typed.ActorRef;
import java.util.Objects;
import java.util.Set;

/**
 * Event emitted by the keypad when a PIN code and selected zones are submitted.
 */
public record PinSubmitted(String pin, Set<String> selectedZones, ActorRef<KeypadActor.Command> keypadRef) {
    public PinSubmitted {
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(selectedZones, "selectedZones");
        selectedZones = Set.copyOf(selectedZones);
    }
}

