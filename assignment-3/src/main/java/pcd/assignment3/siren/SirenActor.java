package pcd.assignment3.siren;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

/**
 * An actor representing the physical siren in the smart home alarm system.
 * It changes state between sounding (activated) and silent (deactivated) based on commands.
 */
public class SirenActor extends AbstractBehavior<SirenActor.Command> {

    /**
     * Interface for all commands accepted by the SirenActor.
     */
    public interface Command {}

    /**
     * Command to activate the siren (make it start sounding).
     */
    public record Activate() implements Command {}

    /**
     * Command to deactivate the siren (silence it).
     */
    public record Deactivate() implements Command {}

    /**
     * Factory method to create a SirenActor behavior.
     *
     * @return the behavior of the SirenActor
     */
    public static Behavior<Command> create() {
        return Behaviors.setup(SirenActor::new);
    }

    private SirenActor(ActorContext<Command> context) {
        super(context);
    }

    @Override
    public Receive<Command> createReceive() {
        return silent();
    }

    /**
     * Behavior when the siren is silent.
     */
    private Receive<Command> silent() {
        return newReceiveBuilder()
                .onMessage(Activate.class, cmd -> {
                    getContext().getLog().warn("SIREN ACTIVATED: WUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUU!");
                    System.out.println("\n>>> [SIREN ON] WUUUUU WUUUUU WUUUUU <<<\n");
                    return Behaviors.receive(Command.class)
                            .onMessage(Deactivate.class, this::onDeactivateInActive)
                            .onMessage(Activate.class, this::onIgnore)
                            .build();
                })
                .onMessage(Deactivate.class, this::onIgnore)
                .build();
    }

    private Behavior<Command> onDeactivateInActive(Deactivate cmd) {
        getContext().getLog().info("Siren deactivated.");
        System.out.println("\n>>> [SIREN OFF] Siren silenced. <<<\n");
        return Behaviors.setup(SirenActor::new); // transition back to initial silent state
    }

    private Behavior<Command> onIgnore(Command cmd) {
        // Ignore duplicate active state commands
        return Behaviors.same();
    }
}
