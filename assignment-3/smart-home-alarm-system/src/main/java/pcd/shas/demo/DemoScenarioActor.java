package pcd.shas.demo;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import pcd.shas.AlarmConfiguration;
import pcd.shas.common.Zone;
import pcd.shas.controlunit.ControlUnitActor;
import pcd.shas.keypad.KeypadActor;
import pcd.shas.sensor.SensorActor;
import pcd.shas.siren.SirenActor;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Scripted demo that drives the complete smart home alarm scenario.
 */
public final class DemoScenarioActor extends AbstractBehavior<DemoScenarioActor.Command> {

    /**
     * Timer key for scheduling the next step of the demo scenario.
     */
    private static final Object NEXT_STEP_TIMER = "next-step";

    /**
     * Gap duration between individual demo actions.
     */
    private static final Duration STEP_GAP = Duration.ofMillis(150);

    /**
     * Grace duration before shutting down system after demo completes.
     */
    private static final Duration FINAL_GRACE = Duration.ofMillis(300);

    /**
     * Root protocol for the demo scenario.
     */
    public interface Command {}

    /**
     * Starts the scripted run.
     */
    public record Start() implements Command {}

    /**
     * Internal command to advance the scenario state machine to the next step.
     */
    private record Advance() implements Command {}

    /**
     * Internal adapter message wrapping a control unit state snapshot.
     *
     * @param snapshot the control unit state snapshot
     */
    private record ControlStateObserved(ControlUnitActor.StateSnapshot snapshot) implements Command {}

    /**
     * Internal adapter message wrapping a siren state snapshot.
     *
     * @param snapshot the siren state snapshot
     */
    private record SirenStateObserved(SirenActor.StateSnapshot snapshot) implements Command {}

    /**
     * Steps in the demo scenario state sequence.
     */
    private enum Step {
        /** Initial state. */
        START,
        /** State after PIN submission. */
        AFTER_PIN,
        /** State after exit delay expiration. */
        AFTER_EXIT_DELAY,
        /** State after sensor activation while armed. */
        AFTER_SENSOR_IN_ARMED,
        /** State after entry delay expiration. */
        AFTER_ENTRY_DELAY,
        /** State after disarm PIN entry. */
        AFTER_DISARM,
        /** State when partial night mode is configured. */
        NIGHT_MODE_CONFIGURED,
        /** State during night mode exit delay. */
        NIGHT_MODE_EXIT_DELAY,
        /** State when armed in night mode. */
        NIGHT_MODE_ARMED,
        /** State during night mode entry delay. */
        NIGHT_MODE_ENTRY_DELAY,
        /** State after night mode disarm. */
        NIGHT_MODE_DISARMED,
        /** Final state before stopping. */
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

    private Step step = Step.START;
    private String pendingControlLabel = "";
    private String pendingSirenLabel = "";

    /**
     * Creates the demo scenario actor behavior.
     *
     * @param configuration alarm configuration containing PIN and delay parameters
     * @return the demo actor behavior
     * @throws NullPointerException if {@code configuration} is null
     */
    public static Behavior<Command> create(AlarmConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");

        return Behaviors.setup(context -> Behaviors.withTimers(timers -> {
            ActorRef<ControlUnitActor.StateSnapshot> controlStateAdapter =
                context.messageAdapter(ControlUnitActor.StateSnapshot.class, ControlStateObserved::new);
            ActorRef<SirenActor.StateSnapshot> sirenStateAdapter =
                context.messageAdapter(SirenActor.StateSnapshot.class, SirenStateObserved::new);

            ActorRef<SirenActor.Command> siren = context.spawn(SirenActor.create(), "siren");
            ActorRef<ControlUnitActor.Command> controlUnit = context.spawn(
                ControlUnitActor.create(
                    configuration.correctPin(),
                    configuration.exitDelay(),
                    configuration.entryDelay(),
                    siren
                ),
                "control-unit"
            );
            ActorRef<KeypadActor.Command> keypad = context.spawn(KeypadActor.create(controlUnit), "keypad");
            ActorRef<SensorActor.Command> perimeterSensor = context.spawn(
                SensorActor.create("front_door", pcd.shas.common.SensorType.DOOR_WINDOW, Zone.PERIMETER, controlUnit),
                "front-door-sensor"
            );
            ActorRef<SensorActor.Command> livingRoomSensor = context.spawn(
                SensorActor.create("living_room_motion", pcd.shas.common.SensorType.MOTION, Zone.LIVING_AREA, controlUnit),
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
                configuration.exitDelay(),
                configuration.entryDelay()
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

    /**
     * Handler for the Start command, initiating step 1 of the demo.
     */
    private Behavior<Command> onStart(Start command) {
        getContext().getLog().info("Demo step 1: system starts in DISARMED");
        queryState("initial state");
        getContext().getLog().info("Demo step 2: correct PIN is submitted through KeypadActor");
        pressPin("1234");
        queryState("after correct PIN submission");
        step = Step.AFTER_PIN;
        timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
        return this;
    }

    /**
     * Handler for Advance command, stepping through the scripted demo sequence.
     */
    private Behavior<Command> onAdvance(Advance command) {
        switch (step) {
            case AFTER_PIN -> {
                getContext().getLog().info("Demo step 4: sensor event during EXIT_DELAY is ignored");
                perimeterSensor.tell(new SensorActor.Activate());
                queryState("after sensor activation during EXIT_DELAY");
                step = Step.AFTER_EXIT_DELAY;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), exitDelay.plus(STEP_GAP));
            }
            case AFTER_EXIT_DELAY -> {
                getContext().getLog().info("Demo step 5: system automatically enters ARMED");
                queryState("after exit-delay expiration");
                getContext().getLog().info("Demo step 6: a sensor is activated");
                perimeterSensor.tell(new SensorActor.Activate());
                queryState("after armed sensor activation");
                step = Step.AFTER_SENSOR_IN_ARMED;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case AFTER_SENSOR_IN_ARMED -> {
                getContext().getLog().info("Demo step 7: system enters ENTRY_DELAY");
                queryState("during entry delay");
                getContext().getLog().info("Demo step 8: entry delay expires");
                step = Step.AFTER_ENTRY_DELAY;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), entryDelay.plus(STEP_GAP));
            }
            case AFTER_ENTRY_DELAY -> {
                getContext().getLog().info("Demo step 9: system enters ALARM and activates the siren");
                getContext().getLog().info("Demo step 10: correct PIN is submitted");
                pressPin("1234");
                step = Step.AFTER_DISARM;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case AFTER_DISARM -> {
                queryState("after disarm");
                querySirenState("after disarm");
                getContext().getLog().info("Demo step 11: system returns to DISARMED and the siren deactivates");
                getContext().getLog().info("Demo step 12: night mode partial arming is configured for PERIMETER and GROUND_FLOOR");
                controlUnit.tell(new ControlUnitActor.ArmPartial(Set.of(Zone.PERIMETER, Zone.GROUND_FLOOR)));
                pressPin("1234");
                queryState("after night-mode PIN submission");
                step = Step.NIGHT_MODE_CONFIGURED;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), exitDelay.plus(STEP_GAP));
            }
            case NIGHT_MODE_CONFIGURED -> {
                getContext().getLog().info("Demo step 13: system automatically enters ARMED in night mode");
                queryState("after night-mode exit delay");
                getContext().getLog().info("Demo step 14: a sensor in an inactive zone is ignored");
                livingRoomSensor.tell(new SensorActor.Activate());
                queryState("after inactive-zone sensor activation");
                step = Step.NIGHT_MODE_EXIT_DELAY;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case NIGHT_MODE_EXIT_DELAY -> {
                getContext().getLog().info("Demo step 15: a sensor in an active zone enters ENTRY_DELAY");
                perimeterSensor.tell(new SensorActor.Activate());
                queryState("after active-zone sensor activation");
                step = Step.NIGHT_MODE_ARMED;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case NIGHT_MODE_ARMED -> {
                getContext().getLog().info("Demo step 16: correct PIN returns the system to DISARMED");
                pressPin("1234");
                step = Step.NIGHT_MODE_DISARMED;
                timers.startSingleTimer(NEXT_STEP_TIMER, new Advance(), STEP_GAP);
            }
            case NIGHT_MODE_DISARMED -> {
                queryState("after night-mode disarm");
                getContext().getLog().info("Demo step 17: night mode ends and the system is disarmed again");
                step = Step.STOPPING;
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

    /**
     * Handler for control unit state query responses.
     */
    private Behavior<Command> onControlStateObserved(ControlStateObserved observed) {
        getContext().getLog().info(
            "Control unit reports {}: {}",
            pendingControlLabel,
            observed.snapshot().state()
        );
        return this;
    }

    /**
     * Handler for siren state query responses.
     */
    private Behavior<Command> onSirenStateObserved(SirenStateObserved observed) {
        getContext().getLog().info(
            "Siren reports {}: {}",
            pendingSirenLabel,
            observed.snapshot().active() ? "ACTIVE" : "INACTIVE"
        );
        return this;
    }

    /**
     * Sends a state query request to the control unit with a logging label.
     */
    private void queryState(String label) {
        pendingControlLabel = label;
        controlUnit.tell(new ControlUnitActor.QueryState(controlStateAdapter));
    }

    /**
     * Sends a state query request to the siren actor with a logging label.
     */
    private void querySirenState(String label) {
        pendingSirenLabel = label;
        siren.tell(new SirenActor.QueryState(sirenStateAdapter));
    }

    /**
     * Simulates pressing key digits on the keypad followed by the '#' submit key.
     *
     * @param pin the PIN string to submit
     */
    private void pressPin(String pin) {
        for (char character : pin.toCharArray()) {
            keypad.tell(new KeypadActor.PressKey(character));
        }
        keypad.tell(new KeypadActor.PressKey('#'));
    }
}
