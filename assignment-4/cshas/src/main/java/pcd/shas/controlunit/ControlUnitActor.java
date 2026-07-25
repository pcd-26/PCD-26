package pcd.shas.controlunit;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.apache.pekko.actor.typed.receptionist.Receptionist;
import org.apache.pekko.actor.typed.receptionist.ServiceKey;
import pcd.shas.common.AlarmState;
import pcd.shas.common.MySerializable;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.Zone;
import pcd.shas.siren.SirenActor;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Clustered control unit for the smart home alarm system.
 *
 * <p>This actor owns the logical alarm state, the active arming zones, the
 * discovered siren targets, and a generation counter used to ignore stale
 * timeout messages. It accepts PIN submissions, sensor activations, arming
 * commands, receptionist updates, and timeout messages. It emits state
 * snapshots for tests, registers itself with the receptionist, and forwards
 * siren commands to the discovered siren actors.</p>
 *
 * <p>The state machine starts in {@link AlarmState#RECOVERY} after creation or
 * recreation and only moves to {@link AlarmState#DISARMED} after the correct
 * PIN is submitted.</p>
 */
public final class ControlUnitActor {

    public static final ServiceKey<Command> CONTROL_UNIT_SERVICE_KEY =
            ServiceKey.create(Command.class, "control-unit-service");

    private static final Object EXIT_DELAY_TIMER_KEY = "exit-delay";
    private static final Object ENTRY_DELAY_TIMER_KEY = "entry-delay";
    private static final Set<Zone> FULL_ARMS = Set.copyOf(EnumSet.allOf(Zone.class));

    private ControlUnitActor() {
        // Utility class.
    }

    /**
     * Root protocol for the control unit.
     */
    public interface Command extends MySerializable {}

    /**
     * Submission of a PIN code.
     *
     * @param pin the submitted PIN
     */
    public record PinSubmitted(String pin) implements Command {
        public PinSubmitted {
            Objects.requireNonNull(pin, "pin");
        }
    }

    /**
     * Sensor activation event received from a distributed sensor actor.
     *
     * @param sensorInfo the activated sensor information
     */
    public record SensorActivated(SensorInfo sensorInfo) implements Command {
        public SensorActivated {
            Objects.requireNonNull(sensorInfo, "sensorInfo");
        }
    }

    /**
     * Configures the next arming cycle to activate all zones.
     */
    public record ArmAll() implements Command {}

    /**
     * Configures the next arming cycle to activate only the selected zones.
     *
     * @param activeZones immutable set of zones that should be active when armed
     */
    public record ArmPartial(Set<Zone> activeZones) implements Command {
        public ArmPartial {
            Objects.requireNonNull(activeZones, "activeZones");
            if (activeZones.isEmpty()) {
                throw new IllegalArgumentException("activeZones cannot be empty");
            }
            activeZones = Set.copyOf(activeZones);
        }
    }

    /**
     * Query for the current logical alarm state.
     *
     * @param replyTo actor that should receive the state snapshot
     */
    public record QueryState(ActorRef<StateSnapshot> replyTo) implements Command {
        public QueryState {
            Objects.requireNonNull(replyTo, "replyTo");
        }
    }

    /**
     * Immutable snapshot of the current alarm state.
     *
     * @param state the current alarm state
     */
    public record StateSnapshot(AlarmState state) implements MySerializable {
        public StateSnapshot {
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * Internal timeout message for the exit-delay timer.
     *
     * @param generation state generation captured when the timer was started
     */
    record ExitDelayTimeout(long generation) implements Command {}

    /**
     * Internal timeout message for the entry-delay timer.
     *
     * @param generation state generation captured when the timer was started
     */
    record EntryDelayTimeout(long generation) implements Command {}

    /**
     * Internal receptionist update carrying the currently discovered sirens.
     *
     * @param sirens the discovered siren actor references
     */
    record SirensUpdated(Set<ActorRef<SirenActor.Command>> sirens) implements Command {
        public SirensUpdated {
            Objects.requireNonNull(sirens, "sirens");
            sirens = Set.copyOf(sirens);
        }
    }

    private record ControlState(
            AlarmState state,
            Set<Zone> armedZones,
            Set<ActorRef<SirenActor.Command>> sirens,
            long generation
    ) {
        private ControlState {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(armedZones, "armedZones");
            Objects.requireNonNull(sirens, "sirens");
            armedZones = Set.copyOf(armedZones);
            sirens = Set.copyOf(sirens);
        }

        private ControlState withState(AlarmState nextState, Set<Zone> nextZones, long nextGeneration) {
            return new ControlState(nextState, nextZones, sirens, nextGeneration);
        }

        private ControlState withSirens(Set<ActorRef<SirenActor.Command>> nextSirens) {
            return new ControlState(state, armedZones, nextSirens, generation);
        }
    }

    /**
     * Creates the control unit with the default exit and entry delays.
     *
     * @param configuredPin the alarm PIN
     * @return the typed behavior
     */
    public static Behavior<Command> create(String configuredPin) {
        return create(configuredPin, Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    /**
     * Creates the control unit with custom delay durations.
     *
     * @param configuredPin the alarm PIN
     * @param exitDelayDuration delay before the system becomes armed
     * @param entryDelayDuration delay before the system enters alarm after intrusion
     * @return the typed behavior
     */
    public static Behavior<Command> create(
            String configuredPin,
            Duration exitDelayDuration,
            Duration entryDelayDuration
    ) {
        validateConfiguredPin(configuredPin);
        Objects.requireNonNull(exitDelayDuration, "exitDelayDuration");
        Objects.requireNonNull(entryDelayDuration, "entryDelayDuration");

        return Behaviors.setup(context -> {
            context.getSystem().receptionist().tell(Receptionist.register(CONTROL_UNIT_SERVICE_KEY, context.getSelf()));
            context.getLog().info("ControlUnitActor registered with receptionist");

            ActorRef<Receptionist.Listing> sirenAdapter = context.messageAdapter(
                    Receptionist.Listing.class,
                    listing -> new SirensUpdated(listing.getServiceInstances(SirenActor.SIREN_SERVICE_KEY))
            );
            context.getSystem().receptionist().tell(Receptionist.subscribe(SirenActor.SIREN_SERVICE_KEY, sirenAdapter));

            return Behaviors.withTimers(timers ->
                    recovery(
                            context,
                            timers,
                            configuredPin,
                            exitDelayDuration,
                            entryDelayDuration,
                            new ControlState(AlarmState.RECOVERY, FULL_ARMS, Set.of(), 0L)
                    )
            );
        });
    }

    private static Behavior<Command> recovery(
            ActorContext<Command> context,
            TimerScheduler<Command> timers,
            String configuredPin,
            Duration exitDelayDuration,
            Duration entryDelayDuration,
            ControlState state
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(SirensUpdated.class, message -> {
                    context.getLog().info("Sirens updated in RECOVERY: {}", message.sirens());
                    return recovery(context, timers, configuredPin, exitDelayDuration, entryDelayDuration, state.withSirens(message.sirens()));
                })
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        context.getLog().info("Transition RECOVERY -> DISARMED");
                        deactivateAll(state.sirens());
                        return disarmed(
                                context,
                                timers,
                                configuredPin,
                                exitDelayDuration,
                                entryDelayDuration,
                                state.withState(AlarmState.DISARMED, FULL_ARMS, state.generation() + 1)
                        );
                    }
                    context.getLog().warn("Incorrect PIN submitted in RECOVERY: {}", message.pin());
                    return Behaviors.same();
                })
                .onMessage(SensorActivated.class, message -> {
                    context.getLog().info(
                            "Ignoring sensor activation while in RECOVERY: sensor={}, type={}, zone={}",
                            message.sensorInfo().id(),
                            message.sensorInfo().type(),
                            message.sensorInfo().zone()
                    );
                    return Behaviors.same();
                })
                .onMessage(ArmAll.class, message -> {
                    context.getLog().info("Ignoring ArmAll command while in RECOVERY");
                    return Behaviors.same();
                })
                .onMessage(ArmPartial.class, message -> {
                    context.getLog().info("Ignoring ArmPartial command while in RECOVERY");
                    return Behaviors.same();
                })
                .onMessage(ExitDelayTimeout.class, message -> ignoreStaleTimeout())
                .onMessage(EntryDelayTimeout.class, message -> ignoreStaleTimeout())
                .onMessage(QueryState.class, message -> {
                    message.replyTo().tell(new StateSnapshot(AlarmState.RECOVERY));
                    return Behaviors.same();
                })
                .build();
    }

    private static Behavior<Command> disarmed(
            ActorContext<Command> context,
            TimerScheduler<Command> timers,
            String configuredPin,
            Duration exitDelayDuration,
            Duration entryDelayDuration,
            ControlState state
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(SirensUpdated.class, message -> {
                    context.getLog().info("Sirens updated in DISARMED: {}", message.sirens());
                    return disarmed(context, timers, configuredPin, exitDelayDuration, entryDelayDuration, state.withSirens(message.sirens()));
                })
                .onMessage(ArmAll.class, message -> {
                    context.getLog().info("Configuring full arming");
                    return disarmed(context, timers, configuredPin, exitDelayDuration, entryDelayDuration, state.withState(AlarmState.DISARMED, FULL_ARMS, state.generation() + 1));
                })
                .onMessage(ArmPartial.class, message -> {
                    context.getLog().info("Configuring partial arming for zones {}", message.activeZones());
                    return disarmed(context, timers, configuredPin, exitDelayDuration, entryDelayDuration, state.withState(AlarmState.DISARMED, message.activeZones(), state.generation() + 1));
                })
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        long nextGeneration = state.generation() + 1;
                        context.getLog().info("Transition DISARMED -> EXIT_DELAY");
                        timers.startSingleTimer(EXIT_DELAY_TIMER_KEY, new ExitDelayTimeout(nextGeneration), exitDelayDuration);
                        return exitDelay(
                                context,
                                timers,
                                configuredPin,
                                exitDelayDuration,
                                entryDelayDuration,
                                state.withState(AlarmState.EXIT_DELAY, state.armedZones(), nextGeneration)
                        );
                    }
                    context.getLog().info("Ignoring PIN submission while DISARMED");
                    return Behaviors.same();
                })
                .onMessage(SensorActivated.class, message -> {
                    context.getLog().info(
                            "Ignoring sensor activation while DISARMED: sensor={}, type={}, zone={}",
                            message.sensorInfo().id(),
                            message.sensorInfo().type(),
                            message.sensorInfo().zone()
                    );
                    return Behaviors.same();
                })
                .onMessage(ExitDelayTimeout.class, message -> ignoreStaleTimeout())
                .onMessage(EntryDelayTimeout.class, message -> ignoreStaleTimeout())
                .onMessage(QueryState.class, message -> {
                    message.replyTo().tell(new StateSnapshot(AlarmState.DISARMED));
                    return Behaviors.same();
                })
                .build();
    }

    private static Behavior<Command> exitDelay(
            ActorContext<Command> context,
            TimerScheduler<Command> timers,
            String configuredPin,
            Duration exitDelayDuration,
            Duration entryDelayDuration,
            ControlState state
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(SirensUpdated.class, message -> {
                    context.getLog().info("Sirens updated in EXIT_DELAY: {}", message.sirens());
                    return exitDelay(context, timers, configuredPin, exitDelayDuration, entryDelayDuration, state.withSirens(message.sirens()));
                })
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        context.getLog().info("Transition EXIT_DELAY -> DISARMED");
                        timers.cancel(EXIT_DELAY_TIMER_KEY);
                        deactivateAll(state.sirens());
                        return disarmed(
                                context,
                                timers,
                                configuredPin,
                                exitDelayDuration,
                                entryDelayDuration,
                                state.withState(AlarmState.DISARMED, FULL_ARMS, state.generation() + 1)
                        );
                    }
                    context.getLog().info("Ignoring PIN submission while EXIT_DELAY is active");
                    return Behaviors.same();
                })
                .onMessage(SensorActivated.class, message -> {
                    context.getLog().info(
                            "Ignoring sensor activation while EXIT_DELAY is active: sensor={}, type={}, zone={}",
                            message.sensorInfo().id(),
                            message.sensorInfo().type(),
                            message.sensorInfo().zone()
                    );
                    return Behaviors.same();
                })
                .onMessage(ExitDelayTimeout.class, message -> {
                    if (message.generation() != state.generation()) {
                        return Behaviors.same();
                    }
                    timers.cancel(EXIT_DELAY_TIMER_KEY);
                    context.getLog().info("Transition EXIT_DELAY -> ARMED");
                    return armed(
                            context,
                            timers,
                            configuredPin,
                            exitDelayDuration,
                            entryDelayDuration,
                            state.withState(AlarmState.ARMED, state.armedZones(), state.generation())
                    );
                })
                .onMessage(EntryDelayTimeout.class, message -> ignoreStaleTimeout())
                .onMessage(QueryState.class, message -> {
                    message.replyTo().tell(new StateSnapshot(AlarmState.EXIT_DELAY));
                    return Behaviors.same();
                })
                .build();
    }

    private static Behavior<Command> armed(
            ActorContext<Command> context,
            TimerScheduler<Command> timers,
            String configuredPin,
            Duration exitDelayDuration,
            Duration entryDelayDuration,
            ControlState state
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(SirensUpdated.class, message -> {
                    context.getLog().info("Sirens updated in ARMED: {}", message.sirens());
                    return armed(context, timers, configuredPin, exitDelayDuration, entryDelayDuration, state.withSirens(message.sirens()));
                })
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        context.getLog().info("Transition ARMED -> DISARMED");
                        timers.cancel(ENTRY_DELAY_TIMER_KEY);
                        deactivateAll(state.sirens());
                        return disarmed(
                                context,
                                timers,
                                configuredPin,
                                exitDelayDuration,
                                entryDelayDuration,
                                state.withState(AlarmState.DISARMED, FULL_ARMS, state.generation() + 1)
                        );
                    }
                    context.getLog().info("Ignoring PIN submission while ARMED");
                    return Behaviors.same();
                })
                .onMessage(SensorActivated.class, message -> {
                    if (state.armedZones().contains(message.sensorInfo().zone())) {
                        long nextGeneration = state.generation() + 1;
                        context.getLog().info(
                                "Transition ARMED -> ENTRY_DELAY due to sensor activation: sensor={}, type={}, zone={}",
                                message.sensorInfo().id(),
                                message.sensorInfo().type(),
                                message.sensorInfo().zone()
                        );
                        timers.startSingleTimer(ENTRY_DELAY_TIMER_KEY, new EntryDelayTimeout(nextGeneration), entryDelayDuration);
                        return entryDelay(
                                context,
                                timers,
                                configuredPin,
                                exitDelayDuration,
                                entryDelayDuration,
                                state.withState(AlarmState.ENTRY_DELAY, state.armedZones(), nextGeneration)
                        );
                    }
                    context.getLog().info(
                            "Ignoring sensor activation while ARMED because zone {} is inactive: sensor={}, type={}",
                            message.sensorInfo().zone(),
                            message.sensorInfo().id(),
                            message.sensorInfo().type()
                    );
                    return Behaviors.same();
                })
                .onMessage(ExitDelayTimeout.class, message -> ignoreStaleTimeout())
                .onMessage(EntryDelayTimeout.class, message -> ignoreStaleTimeout())
                .onMessage(QueryState.class, message -> {
                    message.replyTo().tell(new StateSnapshot(AlarmState.ARMED));
                    return Behaviors.same();
                })
                .build();
    }

    private static Behavior<Command> entryDelay(
            ActorContext<Command> context,
            TimerScheduler<Command> timers,
            String configuredPin,
            Duration exitDelayDuration,
            Duration entryDelayDuration,
            ControlState state
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(SirensUpdated.class, message -> {
                    context.getLog().info("Sirens updated in ENTRY_DELAY: {}", message.sirens());
                    return entryDelay(context, timers, configuredPin, exitDelayDuration, entryDelayDuration, state.withSirens(message.sirens()));
                })
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        context.getLog().info("Transition ENTRY_DELAY -> DISARMED");
                        timers.cancel(ENTRY_DELAY_TIMER_KEY);
                        deactivateAll(state.sirens());
                        return disarmed(
                                context,
                                timers,
                                configuredPin,
                                exitDelayDuration,
                                entryDelayDuration,
                                state.withState(AlarmState.DISARMED, FULL_ARMS, state.generation() + 1)
                        );
                    }
                    context.getLog().info("Ignoring PIN submission while ENTRY_DELAY is active");
                    return Behaviors.same();
                })
                .onMessage(SensorActivated.class, message -> {
                    context.getLog().info(
                            "Ignoring sensor activation while ENTRY_DELAY is active: sensor={}, type={}, zone={}",
                            message.sensorInfo().id(),
                            message.sensorInfo().type(),
                            message.sensorInfo().zone()
                    );
                    return Behaviors.same();
                })
                .onMessage(EntryDelayTimeout.class, message -> {
                    if (message.generation() != state.generation()) {
                        return Behaviors.same();
                    }
                    timers.cancel(ENTRY_DELAY_TIMER_KEY);
                    context.getLog().info("Transition ENTRY_DELAY -> ALARM");
                    activateAll(state.sirens());
                    return alarm(
                            context,
                            timers,
                            configuredPin,
                            exitDelayDuration,
                            entryDelayDuration,
                            state.withState(AlarmState.ALARM, state.armedZones(), state.generation())
                    );
                })
                .onMessage(ExitDelayTimeout.class, message -> ignoreStaleTimeout())
                .onMessage(QueryState.class, message -> {
                    message.replyTo().tell(new StateSnapshot(AlarmState.ENTRY_DELAY));
                    return Behaviors.same();
                })
                .build();
    }

    private static Behavior<Command> alarm(
            ActorContext<Command> context,
            TimerScheduler<Command> timers,
            String configuredPin,
            Duration exitDelayDuration,
            Duration entryDelayDuration,
            ControlState state
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(SirensUpdated.class, message -> {
                    context.getLog().info("Sirens updated in ALARM: {}", message.sirens());
                    return alarm(context, timers, configuredPin, exitDelayDuration, entryDelayDuration, state.withSirens(message.sirens()));
                })
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        context.getLog().info("Transition ALARM -> DISARMED");
                        deactivateAll(state.sirens());
                        return disarmed(
                                context,
                                timers,
                                configuredPin,
                                exitDelayDuration,
                                entryDelayDuration,
                                state.withState(AlarmState.DISARMED, FULL_ARMS, state.generation() + 1)
                        );
                    }
                    context.getLog().info("Ignoring PIN submission while ALARM is active");
                    return Behaviors.same();
                })
                .onMessage(SensorActivated.class, message -> {
                    context.getLog().info(
                            "Ignoring sensor activation while ALARM is active: sensor={}, type={}, zone={}",
                            message.sensorInfo().id(),
                            message.sensorInfo().type(),
                            message.sensorInfo().zone()
                    );
                    return Behaviors.same();
                })
                .onMessage(ExitDelayTimeout.class, message -> ignoreStaleTimeout())
                .onMessage(EntryDelayTimeout.class, message -> ignoreStaleTimeout())
                .onMessage(QueryState.class, message -> {
                    message.replyTo().tell(new StateSnapshot(AlarmState.ALARM));
                    return Behaviors.same();
                })
                .build();
    }

    private static Behavior<Command> ignoreStaleTimeout() {
        return Behaviors.same();
    }

    private static void deactivateAll(Set<ActorRef<SirenActor.Command>> sirens) {
        sirens.forEach(siren -> siren.tell(new SirenActor.Deactivate()));
    }

    private static void activateAll(Set<ActorRef<SirenActor.Command>> sirens) {
        sirens.forEach(siren -> siren.tell(new SirenActor.Activate()));
    }

    private static void validateConfiguredPin(String configuredPin) {
        Objects.requireNonNull(configuredPin, "configuredPin");
        if (configuredPin.isBlank()) {
            throw new IllegalArgumentException("configuredPin cannot be blank");
        }
    }
}
