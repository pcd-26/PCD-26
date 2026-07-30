package pcd.shas.sensor;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;

import java.util.Objects;

/**
 * Typed reusable sensor actor that forwards activations to the control unit.
 */
public final class SensorActor extends AbstractBehavior<SensorActor.Command> {

    /**
     * Root protocol for the sensor.
     */
    public interface Command {}

    /**
     * Simulates a physical activation of the sensor.
     */
    public record Activate() implements Command {}

    private final String sensorId;
    private final SensorType sensorType;
    private final Zone zone;
    private final ActorRef<ControlUnitActor.Command> controlUnit;

    /**
     * Creates a reusable sensor actor.
     *
     * @param sensorId unique sensor identifier
     * @param sensorType sensor type
     * @param zone installation zone
     * @param controlUnit control unit actor
     * @return the sensor behavior
     */
    public static Behavior<Command> create(
            String sensorId,
            SensorType sensorType,
            Zone zone,
            ActorRef<ControlUnitActor.Command> controlUnit
    ) {
        validate(sensorId, sensorType, zone, controlUnit);
        return Behaviors.setup(context -> new SensorActor(context, sensorId, sensorType, zone, controlUnit));
    }

    private SensorActor(
            ActorContext<Command> context,
            String sensorId,
            SensorType sensorType,
            Zone zone,
            ActorRef<ControlUnitActor.Command> controlUnit
    ) {
        super(context);
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.zone = zone;
        this.controlUnit = controlUnit;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
                .onMessage(Activate.class, this::onActivate)
                .build();
    }

    private Behavior<Command> onActivate(Activate command) {
        getContext().getLog().info(
                "Sensor activated: id={}, type={}, zone={}",
                sensorId,
                sensorType,
                zone
        );
        controlUnit.tell(new ControlUnitActor.SensorActivated(new SensorInfo(sensorId, sensorType, zone)));
        return this;
    }

    private static void validate(String sensorId, SensorType sensorType, Zone zone, ActorRef<ControlUnitActor.Command> controlUnit) {
        Objects.requireNonNull(sensorId, "sensorId");
        Objects.requireNonNull(sensorType, "sensorType");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(controlUnit, "controlUnit");
        if (sensorId.isBlank()) {
            throw new IllegalArgumentException("sensorId cannot be blank");
        }
    }
}
