package pcd.shas.siren;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import org.apache.pekko.actor.typed.receptionist.ServiceKey;
import pcd.shas.common.MySerializable;

import java.util.Objects;

/**
 * Typed siren actor for the alarm system, discovered via Receptionist.
 */
public final class SirenActor {

    /**
     * Service key for receptionist discovery.
     */
    public static final ServiceKey<Command> SIREN_SERVICE_KEY =
            ServiceKey.create(Command.class, "siren-service");

    private SirenActor() {
        // Utility class.
    }

    /**
     * Root protocol for the siren.
     */
    public interface Command extends MySerializable {}

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
    public record StateSnapshot(boolean active) implements MySerializable {}

    /**
     * Creates the typed siren behavior and registers it with the Receptionist.
     *
     * @return the siren behavior
     */
    public static Behavior<Command> create() {
        return Behaviors.setup(context -> {
            // Register with receptionist for cluster discovery
            context.getSystem().receptionist().tell(Receptionist.register(SIREN_SERVICE_KEY, context.getSelf()));
            context.getLog().info("SirenActor registered with receptionist");
            return silent(context);
        });
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
