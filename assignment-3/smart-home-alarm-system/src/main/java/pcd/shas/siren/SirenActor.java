package pcd.shas.siren;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.util.Objects;

public final class SirenActor implements AlertDevice {

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

    public record StateSnapshot(boolean active) {}

    // Creation

    // Starts the siren actor in its silent state.
    public static Behavior<Command> create() {
        return Behaviors.setup(SirenActor::silent);
    }

    // Behaviors

    // Silent and active are separate behaviors, so duplicate commands are naturally idempotent.
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
