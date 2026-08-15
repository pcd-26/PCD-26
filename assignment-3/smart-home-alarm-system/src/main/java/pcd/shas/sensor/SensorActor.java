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

import java.time.Instant;
import java.util.Objects;

public final class SensorActor extends AbstractBehavior<SensorActor.Command> {

    public interface Command {}

    public record Activate() implements Command {}

    private final String sensorId;
    private final SensorType sensorType;
    private final Zone installedZone;
    private final ActorRef<ControlUnitActor.Command> controlUnit;

    // Creates a reusable actor for both motion and door/window sensors.
    public static Behavior<Command> create(
        String sensorId,
        SensorType sensorType,
        Zone installedZone,
        ActorRef<ControlUnitActor.Command> controlUnit
    ) {
        validateSensorSetup(sensorId, sensorType, installedZone, controlUnit);
        return Behaviors.setup(context -> new SensorActor(context, sensorId, sensorType, installedZone, controlUnit));
    }

    private SensorActor(
        ActorContext<Command> context,
        String sensorId,
        SensorType sensorType,
        Zone installedZone,
        ActorRef<ControlUnitActor.Command> controlUnit
    ) {
        super(context);
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.installedZone = installedZone;
        this.controlUnit = controlUnit;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Activate.class, this::onActivated)
            .build();
    }

    // A physical activation becomes a timestamped event and then a message to the central unit.
    private Behavior<Command> onActivated(Activate command) {
        SensorEvent event = new SensorEvent(new SensorInfo(sensorId, sensorType, installedZone), Instant.now());
        getContext().getLog().info(
            "Sensor activated: id={}, type={}, zone={}, timestamp={}",
            event.info().id(),
            event.info().type(),
            event.info().zone(),
            event.timestamp()
        );
        controlUnit.tell(new ControlUnitActor.SensorActivated(event.info()));
        return this;
    }

    private static void validateSensorSetup(
        String sensorId,
        SensorType sensorType,
        Zone installedZone,
        ActorRef<ControlUnitActor.Command> controlUnit
    ) {
        Objects.requireNonNull(sensorId, "sensorId");
        Objects.requireNonNull(sensorType, "sensorType");
        Objects.requireNonNull(installedZone, "installedZone");
        Objects.requireNonNull(controlUnit, "controlUnit");
        if (sensorId.isBlank()) {
            throw new IllegalArgumentException("sensorId cannot be blank");
        }
    }
}
