package pcd.shas.siren;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

import java.util.Objects;

/**
 * Typed siren actor for the alarm system, implementing the AlertDevice abstraction.
 */
public final class SirenActor implements AlertDevice {

    private SirenActor() {} // Utility class

    /**
     * Root protocol for the siren, extending generic alert device commands.
     */
    public interface Command extends AlertDevice.Command {}

    /**
     * Turns the siren on.
     */
    public record Activate() implements Command {}

    /**
     * Turns the siren off.
     */
    public record Deactivate() implements Command {}

    /**
     * Query for the current siren state.
     *
     * @param replyTo actor that should receive the state snapshot
     */
    public record QueryState(ActorRef<StateSnapshot> replyTo) implements Command {
        public QueryState {
            Objects.requireNonNull(replyTo, "replyTo");
        }
    }

    /**
     * Snapshot of the current siren state.
     *
     * @param active whether the siren is currently on
     */
    public record StateSnapshot(boolean active) {}

    /**
     * Creates the typed siren behavior.
     *
     * @return the siren behavior
     */
    public static Behavior<Command> create() {
        return Behaviors.setup(SirenActor::silent);
    }

    private static Behavior<Command> silent(ActorContext<Command> context) {
        return Behaviors.receive(Command.class)
            .onMessage(Activate.class, message -> {
                context.getLog().info("Transition SIREN_OFF -> SIREN_ON");
                return active(context);
            })
            .onMessage(Deactivate.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> {
                message.replyTo().tell(new StateSnapshot(false));
                return Behaviors.same();
            })
            .build();
    }

    private static Behavior<Command> active(ActorContext<Command> context) {
        return Behaviors.receive(Command.class)
            .onMessage(Activate.class, message -> Behaviors.same())
            .onMessage(Deactivate.class, message -> {
                context.getLog().info("Transition SIREN_ON -> SIREN_OFF");
                return silent(context);
            })
            .onMessage(QueryState.class, message -> {
                message.replyTo().tell(new StateSnapshot(true));
                return Behaviors.same();
            })
            .build();
    }
}
