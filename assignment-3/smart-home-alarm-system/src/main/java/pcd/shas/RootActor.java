package pcd.shas;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.keypad.KeypadActor;
import pcd.shas.sensor.SensorActor;
import pcd.shas.siren.SirenActor;

import java.util.Objects;
import java.util.Set;

public final class RootActor extends AbstractBehavior<RootActor.Command> {

    // Protocol

    public interface Command {}

    public record SubmitPin(String pin) implements Command {}

    public record RequestFullArming(String pin) implements Command {}

    public record RequestPartialArming(String pin, Set<Zone> zones) implements Command {}

    public record ActivateFrontDoor() implements Command {}

    public record ActivateGroundFloor() implements Command {}

    public record ActivateLivingRoom() implements Command {}

    public record ActivateBedroom() implements Command {}

    public record PrintStatus() implements Command {}

    public record Stop() implements Command {}

    private record ControlStateObserved(ControlUnitActor.StateSnapshot snapshot) implements Command {}

    private record SirenStateObserved(SirenActor.StateSnapshot snapshot) implements Command {}

    // Actor references

    private final ActorRef<KeypadActor.Command> keypad;
    private final ActorRef<SensorActor.Command> frontDoorSensor;
    private final ActorRef<SensorActor.Command> groundFloorSensor;
    private final ActorRef<SensorActor.Command> livingRoomSensor;
    private final ActorRef<SensorActor.Command> bedroomSensor;
    private final ActorRef<ControlUnitActor.Command> controlUnit;
    private final ActorRef<SirenActor.Command> siren;
    private final ActorRef<ControlUnitActor.StateSnapshot> controlStateAdapter;
    private final ActorRef<SirenActor.StateSnapshot> sirenStateAdapter;

    // Creation

    // Creates the alarm actor graph and exposes a small command protocol.
    public static Behavior<Command> create(AlarmConfiguration alarmConfiguration) {
        Objects.requireNonNull(alarmConfiguration, "alarmConfiguration");

        return Behaviors.setup(context -> {
            ActorRef<SirenActor.Command> siren = context.spawn(SirenActor.create(), "siren");
            ActorRef<ControlUnitActor.Command> controlUnit = context.spawn(
                ControlUnitActor.create(
                    alarmConfiguration.correctPin(),
                    alarmConfiguration.exitDelay(),
                    alarmConfiguration.entryDelay(),
                    siren
                ),
                "control-unit"
            );

            return new RootActor(
                context,
                context.spawn(KeypadActor.create(controlUnit), "keypad"),
                context.spawn(SensorActor.create("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER, controlUnit), "front-door-sensor"),
                context.spawn(SensorActor.create("ground_floor_window", SensorType.DOOR_WINDOW, Zone.GROUND_FLOOR, controlUnit), "ground-floor-sensor"),
                context.spawn(SensorActor.create("living_room_motion", SensorType.MOTION, Zone.LIVING_AREA, controlUnit), "living-room-sensor"),
                context.spawn(SensorActor.create("bedroom_motion", SensorType.MOTION, Zone.SLEEPING_AREA, controlUnit), "bedroom-sensor"),
                controlUnit,
                siren,
                context.messageAdapter(ControlUnitActor.StateSnapshot.class, ControlStateObserved::new),
                context.messageAdapter(SirenActor.StateSnapshot.class, SirenStateObserved::new)
            );
        });
    }

    private RootActor(
        ActorContext<Command> context,
        ActorRef<KeypadActor.Command> keypad,
        ActorRef<SensorActor.Command> frontDoorSensor,
        ActorRef<SensorActor.Command> groundFloorSensor,
        ActorRef<SensorActor.Command> livingRoomSensor,
        ActorRef<SensorActor.Command> bedroomSensor,
        ActorRef<ControlUnitActor.Command> controlUnit,
        ActorRef<SirenActor.Command> siren,
        ActorRef<ControlUnitActor.StateSnapshot> controlStateAdapter,
        ActorRef<SirenActor.StateSnapshot> sirenStateAdapter
    ) {
        super(context);
        this.keypad = keypad;
        this.frontDoorSensor = frontDoorSensor;
        this.groundFloorSensor = groundFloorSensor;
        this.livingRoomSensor = livingRoomSensor;
        this.bedroomSensor = bedroomSensor;
        this.controlUnit = controlUnit;
        this.siren = siren;
        this.controlStateAdapter = controlStateAdapter;
        this.sirenStateAdapter = sirenStateAdapter;
    }

    // Message routing

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            // Handles a PIN submitted by CLI or demo.
            .onMessage(SubmitPin.class, this::onSubmitPin)
            // Handles a request to arm every zone.
            .onMessage(RequestFullArming.class, this::onRequestFullArming)
            // Handles a request to arm only selected zones.
            .onMessage(RequestPartialArming.class, this::onRequestPartialArming)
            // Handles a simulated front-door sensor activation.
            .onMessage(ActivateFrontDoor.class, this::onActivateFrontDoor)
            // Handles a simulated ground-floor window sensor activation.
            .onMessage(ActivateGroundFloor.class, this::onActivateGroundFloor)
            // Handles a simulated living-room motion sensor activation.
            .onMessage(ActivateLivingRoom.class, this::onActivateLivingRoom)
            // Handles a simulated bedroom motion sensor activation.
            .onMessage(ActivateBedroom.class, this::onActivateBedroom)
            // Handles a CLI status request.
            .onMessage(PrintStatus.class, this::onPrintStatus)
            // Handles the control-unit reply to a status request.
            .onMessage(ControlStateObserved.class, this::onControlStateObserved)
            // Handles the siren reply to a status request.
            .onMessage(SirenStateObserved.class, this::onSirenStateObserved)
            // Handles actor-system shutdown requested by the main program.
            .onMessage(Stop.class, this::onStop)
            .build();
    }

    private Behavior<Command> onSubmitPin(SubmitPin command) {
        keypad.tell(new KeypadActor.SubmitPin(command.pin()));
        return this;
    }

    private Behavior<Command> onRequestFullArming(RequestFullArming command) {
        keypad.tell(new KeypadActor.RequestFullArming(command.pin()));
        return this;
    }

    private Behavior<Command> onRequestPartialArming(RequestPartialArming command) {
        keypad.tell(new KeypadActor.RequestPartialArming(command.pin(), command.zones()));
        return this;
    }

    private Behavior<Command> onActivateFrontDoor(ActivateFrontDoor command) {
        frontDoorSensor.tell(new SensorActor.Activate());
        return this;
    }

    private Behavior<Command> onActivateGroundFloor(ActivateGroundFloor command) {
        groundFloorSensor.tell(new SensorActor.Activate());
        return this;
    }

    private Behavior<Command> onActivateLivingRoom(ActivateLivingRoom command) {
        livingRoomSensor.tell(new SensorActor.Activate());
        return this;
    }

    private Behavior<Command> onActivateBedroom(ActivateBedroom command) {
        bedroomSensor.tell(new SensorActor.Activate());
        return this;
    }

    private Behavior<Command> onPrintStatus(PrintStatus command) {
        controlUnit.tell(new ControlUnitActor.QueryState(controlStateAdapter));
        siren.tell(new SirenActor.QueryState(sirenStateAdapter));
        return this;
    }

    private Behavior<Command> onControlStateObserved(ControlStateObserved observed) {
        System.out.println("[STATUS] Alarm state: " + observed.snapshot().state());
        return this;
    }

    private Behavior<Command> onSirenStateObserved(SirenStateObserved observed) {
        System.out.println("[STATUS] Siren: " + (observed.snapshot().active() ? "ACTIVE" : "INACTIVE"));
        return this;
    }

    private Behavior<Command> onStop(Stop command) {
        return Behaviors.stopped();
    }
}
