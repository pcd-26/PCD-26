package pcd.shas.sensor;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import pcd.shas.common.MySerializable;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SensorActor extends AbstractBehavior<SensorActor.Command> {

    // Protocol

    public interface Command extends MySerializable {}

    public record Activate() implements Command {}

    private record ControlUnitsUpdated(Set<ActorRef<ControlUnitActor.Command>> controlUnits) implements Command {}

    // Sensor setup

    private final String sensorId;
    private final SensorType sensorType;
    private final Zone installedZone;
    private Optional<ActorRef<ControlUnitActor.Command>> controlUnit = Optional.empty();

    // Creation

    // Creates a reusable actor for both motion and door/window sensors.
    public static Behavior<Command> create(
        String sensorId,
        SensorType sensorType,
        Zone installedZone
    ) {
        validateSensorSetup(sensorId, sensorType, installedZone);
        return Behaviors.setup(context -> new SensorActor(context, sensorId, sensorType, installedZone));
    }

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
        Zone installedZone
    ) {
        super(context);
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.installedZone = installedZone;

        ActorRef<Receptionist.Listing> listingAdapter = context.messageAdapter(
            Receptionist.Listing.class,
            listing -> new ControlUnitsUpdated(listing.getServiceInstances(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY))
        );
        context.getSystem().receptionist().tell(
            Receptionist.subscribe(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY, listingAdapter)
        );
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
        this.controlUnit = Optional.of(controlUnit);
    }

    // Message handlers

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(ControlUnitsUpdated.class, this::onControlUnitsUpdated)
            // Handles one sensor activation event.
            .onMessage(Activate.class, this::onActivated)
            .build();
    }

    private Behavior<Command> onControlUnitsUpdated(ControlUnitsUpdated command) {
        controlUnit = command.controlUnits().stream()
            .max(Comparator.comparing(ref -> ref.path().toString()));
        return this;
    }

    // A physical activation becomes a timestamped event and then a message to the central unit.
    private Behavior<Command> onActivated(Activate command) {
        SensorEvent event = new SensorEvent(new SensorInfo(sensorId, sensorType, installedZone), Instant.now());
        getContext().getLog().info(
            "[SENSOR] Event detected. Sensor={}, type={}, zone={}, timestamp={}.",
            event.info().id(),
            event.info().type(),
            event.info().zone(),
            event.timestamp()
        );

        if (controlUnit.isEmpty()) {
            getContext().getLog().warn("[SENSOR] No control unit found in the cluster.");
            return this;
        }

        controlUnit.get().tell(new ControlUnitActor.SensorActivated(event.info()));
        return this;
    }

    // Helpers

    private static void validateSensorSetup(
        String sensorId,
        SensorType sensorType,
        Zone installedZone
    ) {
        Objects.requireNonNull(sensorId, "sensorId");
        Objects.requireNonNull(sensorType, "sensorType");
        Objects.requireNonNull(installedZone, "installedZone");
        if (sensorId.isBlank()) {
            throw new IllegalArgumentException("sensorId cannot be blank");
        }
    }

    private static void validateSensorSetup(
        String sensorId,
        SensorType sensorType,
        Zone installedZone,
        ActorRef<ControlUnitActor.Command> controlUnit
    ) {
        validateSensorSetup(sensorId, sensorType, installedZone);
        Objects.requireNonNull(controlUnit, "controlUnit");
    }
}
