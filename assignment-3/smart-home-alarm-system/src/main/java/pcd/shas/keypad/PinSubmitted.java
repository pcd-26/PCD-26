package pcd.shas.keypad;

import org.apache.pekko.actor.typed.ActorRef;

import java.util.Objects;
import java.util.Set;

public record PinSubmitted(String pin, Set<String> selectedZones, ActorRef<KeypadActor.Command> keypadRef) {
    public PinSubmitted {
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(selectedZones, "selectedZones");
        selectedZones = Set.copyOf(selectedZones);
    }
}
