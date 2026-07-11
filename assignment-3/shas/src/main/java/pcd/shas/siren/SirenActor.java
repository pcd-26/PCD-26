package pcd.shas.siren;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;

/**
 * An actor representing the physical siren in the smart home alarm system.
 * It changes state between sounding (activated) and silent (deactivated) based on AlertDevice commands.
 */
public class SirenActor extends AbstractBehavior<AlertDevice.Command> implements AlertDevice {

    /**
     * Factory method to create a SirenActor behavior.
     *
     * @return the behavior of the SirenActor
     */
    public static Behavior<AlertDevice.Command> create() {
        return Behaviors.setup(SirenActor::new);
    }

    private SirenActor(ActorContext<AlertDevice.Command> context) {
        super(context);
    }

    @Override
    public Receive<AlertDevice.Command> createReceive() {
        return silent();
    }

    /**
     * Behavior when the siren is silent.
     */
    private Receive<AlertDevice.Command> silent() {
        return newReceiveBuilder()
                .onMessage(AlertDevice.Activate.class, cmd -> {
                    getContext().getLog().warn("SIREN ACTIVATED: WUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUUU!");
                    System.out.println("\n>>> [SIREN ON] WUUUUU WUUUUU WUUUUU <<<\n");
                    return Behaviors.receive(AlertDevice.Command.class)
                            .onMessage(AlertDevice.Deactivate.class, this::onDeactivateInActive)
                            .onMessage(AlertDevice.Activate.class, this::onIgnore)
                            .build();
                })
                .onMessage(AlertDevice.Deactivate.class, this::onIgnore)
                .build();
    }

    private Behavior<AlertDevice.Command> onDeactivateInActive(AlertDevice.Deactivate cmd) {
        getContext().getLog().info("Siren deactivated.");
        System.out.println("\n>>> [SIREN OFF] Siren silenced. <<<\n");
        return Behaviors.setup(SirenActor::new); // transition back to initial silent state
    }

    private Behavior<AlertDevice.Command> onIgnore(AlertDevice.Command cmd) {
        // Ignore duplicate active state commands
        return Behaviors.same();
    }
}
