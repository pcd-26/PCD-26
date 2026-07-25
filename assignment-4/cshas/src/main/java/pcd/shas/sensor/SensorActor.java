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

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Cluster-aware sensor actor.
 *
 * <p>The sensor owns its immutable identity metadata and the currently
 * discovered control units. It accepts activation commands, emits
 * {@link ControlUnitActor.SensorActivated} messages, and discovers control
 * units through the receptionist rather than hard-coded actor references.</p>
 */
public final class SensorActor extends AbstractBehavior<SensorActor.Command> {

    /**
     * Root protocol for the sensor.
     */
    public interface Command extends MySerializable {}

    /**
     * Simulates a physical activation of the sensor.
     */
    public record Activate() implements Command {}

    /**
     * Internal receptionist update carrying the currently discovered control
     * unit actor references.
     *
     * @param controlUnits discovered control units
     */
    private record ControlUnitsUpdated(Set<ActorRef<ControlUnitActor.Command>> controlUnits) implements Command {}

    private final String sensorId;
    private final SensorType sensorType;
    private final Zone zone;
    private final Set<ActorRef<ControlUnitActor.Command>> controlUnits = new HashSet<>();

    /**
     * Creates a reusable sensor actor.
     *
     * @param sensorId unique sensor identifier
     * @param sensorType sensor type
     * @param zone installation zone
     * @return the sensor behavior
     */
    public static Behavior<Command> create(
        String sensorId,
        SensorType sensorType,
        Zone zone
    ) {
        validate(sensorId, sensorType, zone);
        return Behaviors.setup(context -> new SensorActor(context, sensorId, sensorType, zone));
    }

    private SensorActor(
        ActorContext<Command> context,
        String sensorId,
        SensorType sensorType,
        Zone zone
    ) {
        super(context);
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.zone = zone;

        // Subscribe to control unit updates from receptionist
        ActorRef<Receptionist.Listing> listingAdapter = context.messageAdapter(
            Receptionist.Listing.class,
            listing -> new ControlUnitsUpdated(listing.getServiceInstances(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY))
        );
        context.getSystem().receptionist().tell(
            Receptionist.subscribe(ControlUnitActor.CONTROL_UNIT_SERVICE_KEY, listingAdapter)
        );
    }

    /**
     * Returns the sensor command handlers.
     *
     * @return the Receive builder for sensor commands
     */
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(ControlUnitsUpdated.class, this::onControlUnitsUpdated)
            .onMessage(Activate.class, this::onActivate)
            .build();
    }

    /**
     * Handles dynamic updates from the receptionist containing active control unit references.
     *
     * @param command update containing the set of discovered control unit actors
     * @return updated behavior
     */
    private Behavior<Command> onControlUnitsUpdated(ControlUnitsUpdated command) {
        getContext().getLog().info("Sensor {} discovered control units: {}", sensorId, command.controlUnits());
        this.controlUnits.clear();
        this.controlUnits.addAll(command.controlUnits());
        return this;
    }

    /**
     * Handles sensor activation events, broadcasting a {@link ControlUnitActor.SensorActivated}
     * message containing metadata to all discovered control units.
     *
     * @param command activation command
     * @return updated behavior
     */
    private Behavior<Command> onActivate(Activate command) {
        getContext().getLog().info(
            "Sensor activated: id={}, type={}, zone={}",
            sensorId,
            sensorType,
            zone
        );

        if (controlUnits.isEmpty()) {
            getContext().getLog().warn("No control unit found in cluster to send sensor activation event");
            return this;
        }

        SensorInfo info = new SensorInfo(sensorId, sensorType, zone);
        for (ActorRef<ControlUnitActor.Command> cu : controlUnits) {
            cu.tell(new ControlUnitActor.SensorActivated(info));
        }
        return this;
    }

    /**
     * Validates sensor construction parameters.
     *
     * @param sensorId unique sensor ID
     * @param sensorType sensor type
     * @param zone installation zone
     */
    private static void validate(String sensorId, SensorType sensorType, Zone zone) {
        Objects.requireNonNull(sensorId, "sensorId");
        Objects.requireNonNull(sensorType, "sensorType");
        Objects.requireNonNull(zone, "zone");
        if (sensorId.isBlank()) {
            throw new IllegalArgumentException("sensorId cannot be blank");
        }
    }
}
