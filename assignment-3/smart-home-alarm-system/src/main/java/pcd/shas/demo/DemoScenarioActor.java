package pcd.shas.demo;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import pcd.shas.AlarmConfiguration;
import pcd.shas.common.SensorType;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.keypad.KeypadActor;
import pcd.shas.sensor.SensorActor;
import pcd.shas.siren.SirenActor;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public final class DemoScenarioActor extends AbstractBehavior<DemoScenarioActor.Command> {

    private static final Object NEXT_STEP_TIMER = "next-step";
    private static final Duration STEP_GAP = Duration.ofMillis(150);
    private static final Duration FINAL_GRACE = Duration.ofMillis(300);

    public interface Command {}

    public record Start() implements Command {}

    private record Advance() implements Command {}

    private record ControlStateObserved(ControlUnitActor.StateSnapshot snapshot) implements Command {}

    private record SirenStateObserved(SirenActor.StateSnapshot snapshot) implements Command {}

    private enum DemoStep {
        START,
        AFTER_PIN,
        AFTER_EXIT_DELAY,
        AFTER_SENSOR_IN_ARMED,
        AFTER_ENTRY_DELAY,
        AFTER_DISARM,
        NIGHT_MODE_CONFIGURED,
        NIGHT_MODE_EXIT_DELAY,
        NIGHT_MODE_ARMED,
        NIGHT_MODE_ENTRY_DELAY,
        NIGHT_MODE_DISARMED,
        STOPPING
    }

    private final TimerScheduler<Command> timers;
    private final ActorRef<KeypadActor.Command> keypad;
    private final ActorRef<SensorActor.Command> perimeterSensor;
    private final ActorRef<SensorActor.Command> livingRoomSensor;
    private final ActorRef<ControlUnitActor.Command> controlUnit;
    private final ActorRef<SirenActor.Command> siren;
    private final ActorRef<ControlUnitActor.StateSnapshot> controlStateAdapter;
    private final ActorRef<SirenActor.StateSnapshot> sirenStateAdapter;
    private final Duration exitDelay;
    private final Duration entryDelay;

    private DemoStep currentStep = DemoStep.START;
    private String pendingControlStateLabel = "";
    private String pendingSirenStateLabel = "";

    // Creates the demo actor and all real actors used in the scripted scenario.
    public static Behavior<Command> create(AlarmConfiguration alarmConfiguration) {
        Objects.requireNonNull(alarmConfiguration, "alarmConfiguration");

        return Behaviors.setup(context -> Behaviors.withTimers(timers -> {
            ActorRef<ControlUnitActor.StateSnapshot> controlStateAdapter =
                context.messageAdapter(ControlUnitActor.StateSnapshot.class, ControlStateObserved::new);
            ActorRef<SirenActor.StateSnapshot> sirenStateAdapter =
                context.messageAdapter(SirenActor.StateSnapshot.class, SirenStateObserved::new);

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
            ActorRef<KeypadActor.Command> keypad = context.spawn(KeypadActor.create(controlUnit), "keypad");
            ActorRef<SensorActor.Command> perimeterSensor = context.spawn(
                SensorActor.create("front_door", SensorType.DOOR_WINDOW, Zone.PERIMETER, controlUnit),
                "front-door-sensor"
            );
            ActorRef<SensorActor.Command> livingRoomSensor = context.spawn(
                SensorActor.create("living_room_motion", SensorType.MOTION, Zone.LIVING_AREA, controlUnit),
                "living-room-sensor"
            );

            return new DemoScenarioActor(
                context,
                timers,
                keypad,
                perimeterSensor,
                livingRoomSensor,
                controlUnit,
                siren,
                controlStateAdapter,
                sirenStateAdapter,
                alarmConfiguration.exitDelay(),
                alarmConfiguration.entryDelay()
            );
        }));
    }

    private DemoScenarioActor(
        ActorContext<Command> context,
        TimerScheduler<Command> timers,
        ActorRef<KeypadActor.Command> keypad,
        ActorRef<SensorActor.Command> perimeterSensor,
        ActorRef<SensorActor.Command> livingRoomSensor,
        ActorRef<ControlUnitActor.Command> controlUnit,
        ActorRef<SirenActor.Command> siren,
        ActorRef<ControlUnitActor.StateSnapshot> controlStateAdapter,
        ActorRef<SirenActor.StateSnapshot> sirenStateAdapter,
        Duration exitDelay,
        Duration entryDelay
    ) {
        super(context);
        this.timers = timers;
        this.keypad = keypad;
        this.perimeterSensor = perimeterSensor;
        this.livingRoomSensor = livingRoomSensor;
        this.controlUnit = controlUnit;
        this.siren = siren;
        this.controlStateAdapter = controlStateAdapter;
        this.sirenStateAdapter = sirenStateAdapter;
        this.exitDelay = exitDelay;
        this.entryDelay = entryDelay;
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Start.class, this::onStart)
            .onMessage(Advance.class, this::onAdvance)
            .onMessage(ControlStateObserved.class, this::onControlStateObserved)
            .onMessage(SirenStateObserved.class, this::onSirenStateObserved)
            .build();
    }

    // Starts the first asynchronous interaction; later steps are scheduled with timers.
    private Behavior<Command> onStart(Start command) {
        getContext().getLog().info("Demo step 1: system starts in DISARMED");
        queryControlState("initial state");
        getContext().getLog().info("Demo step 2: correct PIN is submitted through KeypadActor");
        pressPin("1234");
        queryControlState("after correct PIN submission");
        currentStep = DemoStep.AFTER_PIN;
        timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
        return this;
    }

    // Each case sends messages to actors, then schedules the next observation point.
    private Behavior<Command> onAdvance(Advance command) {
        switch (currentStep) {
            case AFTER_PIN -> {
                getContext().getLog().info("Demo step 4: sensor event during EXIT_DELAY is ignored");
                perimeterSensor.tell(new SensorActor.Activate());
                queryControlState("after sensor activation during EXIT_DELAY");
                currentStep = DemoStep.AFTER_EXIT_DELAY;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), exitDelay.plus(STEP_GAP));
            }
            case AFTER_EXIT_DELAY -> {
                getContext().getLog().info("Demo step 5: system automatically enters ARMED");
                queryControlState("after exit-delay expiration");
                getContext().getLog().info("Demo step 6: a sensor is activated");
                perimeterSensor.tell(new SensorActor.Activate());
                queryControlState("after armed sensor activation");
                currentStep = DemoStep.AFTER_SENSOR_IN_ARMED;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case AFTER_SENSOR_IN_ARMED -> {
                getContext().getLog().info("Demo step 7: system enters ENTRY_DELAY");
                queryControlState("during entry delay");
                getContext().getLog().info("Demo step 8: entry delay expires");
                currentStep = DemoStep.AFTER_ENTRY_DELAY;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), entryDelay.plus(STEP_GAP));
            }
            case AFTER_ENTRY_DELAY -> {
                getContext().getLog().info("Demo step 9: system enters ALARM and activates the siren");
                getContext().getLog().info("Demo step 10: correct PIN is submitted");
                pressPin("1234");
                currentStep = DemoStep.AFTER_DISARM;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case AFTER_DISARM -> {
                queryControlState("after disarm");
                querySirenState("after disarm");
                getContext().getLog().info("Demo step 11: system returns to DISARMED and the siren deactivates");
                getContext().getLog().info("Demo step 12: night mode partial arming is configured for PERIMETER and GROUND_FLOOR");
                keypad.tell(new KeypadActor.ArmPartial(Set.of(Zone.PERIMETER, Zone.GROUND_FLOOR)));
                pressPin("1234");
                queryControlState("after night-mode PIN submission");
                currentStep = DemoStep.NIGHT_MODE_CONFIGURED;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), exitDelay.plus(STEP_GAP));
            }
            case NIGHT_MODE_CONFIGURED -> {
                getContext().getLog().info("Demo step 13: system automatically enters ARMED in night mode");
                queryControlState("after night-mode exit delay");
                currentStep = DemoStep.NIGHT_MODE_ARMED;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case NIGHT_MODE_ARMED -> {
                getContext().getLog().info("Demo step 14: a sensor in an inactive zone is ignored");
                livingRoomSensor.tell(new SensorActor.Activate());
                queryControlState("after inactive-zone sensor activation");
                currentStep = DemoStep.NIGHT_MODE_EXIT_DELAY;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case NIGHT_MODE_EXIT_DELAY -> {
                getContext().getLog().info("Demo step 15: a sensor in an active zone enters ENTRY_DELAY");
                perimeterSensor.tell(new SensorActor.Activate());
                queryControlState("after active-zone sensor activation");
                currentStep = DemoStep.NIGHT_MODE_ENTRY_DELAY;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case NIGHT_MODE_ENTRY_DELAY -> {
                getContext().getLog().info("Demo step 16: correct PIN returns the system to DISARMED");
                queryControlState("during night-mode entry delay");
                pressPin("1234");
                currentStep = DemoStep.NIGHT_MODE_DISARMED;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case NIGHT_MODE_DISARMED -> {
                queryControlState("after night-mode disarm");
                getContext().getLog().info("Demo step 17: night mode ends and the system is disarmed again");
                currentStep = DemoStep.STOPPING;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), FINAL_GRACE);
            }
            case STOPPING -> {
                getContext().getLog().info("Demo complete: stopping the actor system");
                return Behaviors.stopped();
            }
            case START -> {
                return this;
            }
        }

        return this;
    }

    private Behavior<Command> onControlStateObserved(ControlStateObserved observed) {
        getContext().getLog().info(
            "Control unit reports {}: {}",
            pendingControlStateLabel,
            observed.snapshot().state()
        );
        return this;
    }

    private Behavior<Command> onSirenStateObserved(SirenStateObserved observed) {
        getContext().getLog().info(
            "Siren reports {}: {}",
            pendingSirenStateLabel,
            observed.snapshot().active() ? "ACTIVE" : "INACTIVE"
        );
        return this;
    }

    private void queryControlState(String label) {
        pendingControlStateLabel = label;
        controlUnit.tell(new ControlUnitActor.QueryState(controlStateAdapter));
    }

    private void querySirenState(String label) {
        pendingSirenStateLabel = label;
        siren.tell(new SirenActor.QueryState(sirenStateAdapter));
    }

    private void pressPin(String pin) {
        for (char digit : pin.toCharArray()) {
            keypad.tell(new KeypadActor.PressKey(digit));
        }
        keypad.tell(new KeypadActor.PressKey('#'));
    }
}
