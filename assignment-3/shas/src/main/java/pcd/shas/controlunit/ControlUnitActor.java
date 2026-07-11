package pcd.shas.controlunit;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;

import java.util.Set;

/**
 * Typed actor protocol for the smart home alarm control unit.
 *
 * <p>The actor behavior is intentionally minimal at this stage. The project
 * currently focuses on defining the domain model and the message protocol that
 * will be implemented in later tasks.</p>
 */
public final class ControlUnitActor {

    private ControlUnitActor() {
        // Utility class.
    }

    /**
     * Root protocol for the control unit.
     */
    public interface Command {}

    /**
     * Submission of a PIN code from the keypad.
     *
     * @param pin the submitted PIN
     * @param selectedZones the zones selected for partial arming
     * @param replyTo actor that should receive the keypad response
     */
    public record PinSubmitted(String pin, Set<String> selectedZones) implements Command {
        public PinSubmitted {
            selectedZones = Set.copyOf(selectedZones);
        }
    }

    /**
     * Sensor activation event.
     *
     * @param sensorInfo the activated sensor
     */
    public record SensorActivated(SensorInfo sensorInfo) implements Command {}

    /**
     * External request indicating that the exit delay has elapsed.
     */
    public record ExitDelayExpired() implements Command {}

    /**
     * External request indicating that the entry delay has elapsed.
     */
    public record EntryDelayExpired() implements Command {}

    /**
     * Query for the current alarm state, used by tests.
     *
     * @param replyTo actor that should receive the state snapshot
     */
    public record QueryState(ActorRef<StateSnapshot> replyTo) implements Command {}

    /**
     * Snapshot of the current alarm state.
     *
     * @param state the current alarm state
     * @param fullyArmed whether all zones are armed
     * @param activeZones the active zones when the system is partially armed
     */
    public record StateSnapshot(AlarmState state, boolean fullyArmed, Set<String> activeZones) {
        public StateSnapshot {
            activeZones = Set.copyOf(activeZones);
        }
    }

    private record ExitDelayTimeout() implements Command {}

    private record EntryDelayTimeout() implements Command {}

    /**
     * Minimal behavior placeholder for the control unit.
     *
     * @return a no-op behavior that accepts the protocol
     */
    public static Behavior<Command> create() {
        return Behaviors.receiveMessage(message -> Behaviors.same());
    }
}
