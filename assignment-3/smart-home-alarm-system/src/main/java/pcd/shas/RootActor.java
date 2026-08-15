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

    public record ActivateLivingRoom() implements Command {}

    public record PrintStatus() implements Command {}

    public record Stop() implements Command {}

    private record ControlStateObserved(ControlUnitActor.StateSnapshot snapshot) implements Command {}

    private record SirenStateObserved(SirenActor.StateSnapshot snapshot) implements Command {}

    // Actor references

    private final ActorRef<KeypadActor.Command> keypad;
    private final ActorRef<SensorActor.Command> frontDoorSensor;
    private final ActorRef<SensorActor.Command> livingRoomSensor;
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
                context.spawn(SensorActor.create("living_room_motion", SensorType.MOTION, Zone.LIVING_AREA, controlUnit), "living-room-sensor"),
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
        ActorRef<SensorActor.Command> livingRoomSensor,
        ActorRef<ControlUnitActor.Command> controlUnit,
        ActorRef<SirenActor.Command> siren,
        ActorRef<ControlUnitActor.StateSnapshot> controlStateAdapter,
        ActorRef<SirenActor.StateSnapshot> sirenStateAdapter
    ) {
        super(context);
        this.keypad = keypad;
        this.frontDoorSensor = frontDoorSensor;
        this.livingRoomSensor = livingRoomSensor;
        this.controlUnit = controlUnit;
        this.siren = siren;
        this.controlStateAdapter = controlStateAdapter;
        this.sirenStateAdapter = sirenStateAdapter;
    }

    // Message routing

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(SubmitPin.class, this::onSubmitPin)
            .onMessage(RequestFullArming.class, this::onRequestFullArming)
            .onMessage(RequestPartialArming.class, this::onRequestPartialArming)
            .onMessage(ActivateFrontDoor.class, this::onActivateFrontDoor)
            .onMessage(ActivateLivingRoom.class, this::onActivateLivingRoom)
            .onMessage(PrintStatus.class, this::onPrintStatus)
            .onMessage(ControlStateObserved.class, this::onControlStateObserved)
            .onMessage(SirenStateObserved.class, this::onSirenStateObserved)
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

    private Behavior<Command> onActivateLivingRoom(ActivateLivingRoom command) {
        livingRoomSensor.tell(new SensorActor.Activate());
        return this;
    }

    private Behavior<Command> onPrintStatus(PrintStatus command) {
        controlUnit.tell(new ControlUnitActor.QueryState(controlStateAdapter));
        siren.tell(new SirenActor.QueryState(sirenStateAdapter));
        return this;
    }

    private Behavior<Command> onControlStateObserved(ControlStateObserved observed) {
        System.out.println("Alarm state: " + observed.snapshot().state());
        return this;
    }

    private Behavior<Command> onSirenStateObserved(SirenStateObserved observed) {
        System.out.println("Siren: " + (observed.snapshot().active() ? "ACTIVE" : "INACTIVE"));
        return this;
    }

    private Behavior<Command> onStop(Stop command) {
        return Behaviors.stopped();
    }
}
