package pcd.assignment3.sensor;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import pcd.assignment3.common.SensorInfo;
import pcd.assignment3.controlunit.ControlUnitActor;

import java.time.Instant;

/**
 * An actor representing a physical sensor in the smart home alarm system.
 * It receives simulated triggers and forwards them to the Control Unit.
 */
public class SensorActor extends AbstractBehavior<SensorActor.Command> {

    /**
     * Interface for all commands accepted by the SensorActor.
     */
    public interface Command {}

    /**
     * Message representing a simulated trigger event (e.g., motion detected or door opened).
     */
    public record Trigger() implements Command {}

    private final SensorInfo info;
    private final ActorRef<ControlUnitActor.Command> controlUnit;

    /**
     * Factory method to create a SensorActor behavior.
     *
     * @param info        the metadata of the sensor
     * @param controlUnit the reference to the central control unit
     * @return the behavior of the SensorActor
     */
    public static Behavior<Command> create(SensorInfo info, ActorRef<ControlUnitActor.Command> controlUnit) {
        return Behaviors.setup(context -> new SensorActor(context, info, controlUnit));
    }

    private SensorActor(ActorContext<Command> context, SensorInfo info, ActorRef<ControlUnitActor.Command> controlUnit) {
        super(context);
        this.info = info;
        this.controlUnit = controlUnit;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Trigger.class, this::onTrigger)
                .build();
    }

    private Behavior<Command> onTrigger(Trigger trigger) {
        getContext().getLog().info("Sensor '{}' (Type: {}, Zone: {}) was triggered!", info.id(), info.type(), info.zone());
        controlUnit.tell(new ControlUnitActor.SensorTriggered(info, Instant.now()));
        return this;
    }
}
