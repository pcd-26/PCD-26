package pcd.shas.siren;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import org.apache.pekko.actor.typed.receptionist.ServiceKey;
import pcd.shas.common.MySerializable;

import java.util.Objects;

public final class SirenActor implements AlertDevice {

    public static final ServiceKey<Command> SIREN_SERVICE_KEY =
        ServiceKey.create(Command.class, "siren-service");

    private SirenActor() {}

    // Protocol

    public interface Command extends AlertDevice.Command {}

    public record Activate() implements Command {}

    public record Deactivate() implements Command {}

    public record QueryState(ActorRef<StateSnapshot> replyTo) implements Command {
        public QueryState {
            Objects.requireNonNull(replyTo, "replyTo");
        }
    }

    public record StateSnapshot(boolean active) implements MySerializable {}

    // Creation

    // Starts the siren actor in its silent state.
    public static Behavior<Command> create() {
        return Behaviors.setup(context -> {
            context.getSystem().receptionist().tell(Receptionist.register(SIREN_SERVICE_KEY, context.getSelf()));
            return silent(context);
        });
    }

    // Behaviors

    // Silent and active are separate behaviors, so duplicate commands are naturally idempotent.
    private static Behavior<Command> silent(ActorContext<Command> context) {
        return Behaviors.receive(Command.class)
            // Handles siren activation while it is currently silent.
            .onMessage(Activate.class, message -> {
                context.getLog().info("[SIREN] Activated.");
                return active(context);
            })
            // Handles duplicate deactivation while already silent.
            .onMessage(Deactivate.class, message -> Behaviors.same())
            // Handles status queries while the siren is silent.
            .onMessage(QueryState.class, message -> {
                message.replyTo().tell(new StateSnapshot(false));
                return Behaviors.same();
            })
            .build();
    }

    private static Behavior<Command> active(ActorContext<Command> context) {
        return Behaviors.receive(Command.class)
            // Handles duplicate activation while already active.
            .onMessage(Activate.class, message -> Behaviors.same())
            // Handles siren deactivation while it is currently active.
            .onMessage(Deactivate.class, message -> {
                context.getLog().info("[SIREN] Deactivated.");
                return silent(context);
            })
            // Handles status queries while the siren is active.
            .onMessage(QueryState.class, message -> {
                message.replyTo().tell(new StateSnapshot(true));
                return Behaviors.same();
            })
            .build();
    }
}
