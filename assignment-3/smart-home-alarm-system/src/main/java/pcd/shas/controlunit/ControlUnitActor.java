package pcd.shas.controlunit;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;
import pcd.shas.common.Zone;
import pcd.shas.siren.SirenActor;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class ControlUnitActor {

    private static final Object EXIT_DELAY_TIMER = "exit-delay";
    private static final Object ENTRY_DELAY_TIMER = "entry-delay";
    private static final Set<Zone> ALL_ZONES = Set.copyOf(EnumSet.allOf(Zone.class));

    private ControlUnitActor() {}

    public interface Command {}

    public record PinSubmitted(String pin) implements Command {
        public PinSubmitted {
            Objects.requireNonNull(pin, "pin");
        }
    }

    public record SensorActivated(SensorInfo sensorInfo) implements Command {
        public SensorActivated {
            Objects.requireNonNull(sensorInfo, "sensorInfo");
        }
    }

    public record ArmAll() implements Command {}

    public record ArmPartial(Set<Zone> activeZones) implements Command {
        public ArmPartial {
            Objects.requireNonNull(activeZones, "activeZones");
            if (activeZones.isEmpty()) {
                throw new IllegalArgumentException("activeZones cannot be empty");
            }
            activeZones = Set.copyOf(activeZones);
        }
    }

    public record QueryState(ActorRef<StateSnapshot> replyTo) implements Command {
        public QueryState {
            Objects.requireNonNull(replyTo, "replyTo");
        }
    }

    public record StateSnapshot(AlarmState state) {
        public StateSnapshot {
            Objects.requireNonNull(state, "state");
        }
    }

    record ExitDelayTimeout() implements Command {}

    record EntryDelayTimeout() implements Command {}

    // Entry point with the default demo timings.
    public static Behavior<Command> create(String correctPin, ActorRef<SirenActor.Command> siren) {
        return create(correctPin, Duration.ofSeconds(5), Duration.ofSeconds(5), siren);
    }

    // Builds the central state-machine actor and wires Pekko timers into its protocol.
    public static Behavior<Command> create(
        String correctPin,
        Duration exitDelayDuration,
        Duration entryDelayDuration,
        ActorRef<SirenActor.Command> siren
    ) {
        validateCorrectPin(correctPin);
        Objects.requireNonNull(exitDelayDuration, "exitDelayDuration");
        Objects.requireNonNull(entryDelayDuration, "entryDelayDuration");
        Objects.requireNonNull(siren, "siren");

        return Behaviors.setup(context ->
            Behaviors.withTimers(timers ->
                disarmed(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, ALL_ZONES)
            )
        );
    }

    // DISARMED: sensors are ignored; a correct PIN starts the exit delay.
    private static Behavior<Command> disarmed(
        ActorContext<Command> context,
        TimerScheduler<Command> timers,
        String correctPin,
        Duration exitDelayDuration,
        Duration entryDelayDuration,
        ActorRef<SirenActor.Command> siren,
        Set<Zone> zonesForNextArming
    ) {
        return Behaviors.receive(Command.class)
            .onMessage(ArmAll.class, message -> {
                context.getLog().info("Configuring full arming");
                return disarmed(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, ALL_ZONES);
            })
            .onMessage(ArmPartial.class, message -> {
                context.getLog().info("Configuring partial arming for zones {}", message.activeZones());
                return disarmed(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, message.activeZones());
            })
            .onMessage(PinSubmitted.class, message -> {
                if (correctPin.equals(message.pin())) {
                    context.getLog().info("Transition DISARMED -> EXIT_DELAY");
                    timers.startSingleTimer(EXIT_DELAY_TIMER, new ExitDelayTimeout(), exitDelayDuration);
                    return exitDelay(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, zonesForNextArming);
                }

                return stayInSameStateAfterWrongPin(context, AlarmState.DISARMED);
            })
            .onMessage(SensorActivated.class, message ->
                ignoreSensorBecauseStateIsInactive(context, AlarmState.DISARMED, message.sensorInfo()))
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.DISARMED))
            .build();
    }

    // EXIT_DELAY: occupants can leave; sensors still do not count as intrusions.
    private static Behavior<Command> exitDelay(
        ActorContext<Command> context,
        TimerScheduler<Command> timers,
        String correctPin,
        Duration exitDelayDuration,
        Duration entryDelayDuration,
        ActorRef<SirenActor.Command> siren,
        Set<Zone> zonesBeingArmed
    ) {
        return Behaviors.receive(Command.class)
            .onMessage(PinSubmitted.class, message -> {
                if (correctPin.equals(message.pin())) {
                    timers.cancel(EXIT_DELAY_TIMER);
                    context.getLog().info("Transition EXIT_DELAY -> DISARMED");
                    siren.tell(new SirenActor.Deactivate());
                    return disarmed(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, ALL_ZONES);
                }

                return stayInSameStateAfterWrongPin(context, AlarmState.EXIT_DELAY);
            })
            .onMessage(SensorActivated.class, message ->
                ignoreSensorBecauseStateIsInactive(context, AlarmState.EXIT_DELAY, message.sensorInfo()))
            .onMessage(ExitDelayTimeout.class, message -> {
                timers.cancel(EXIT_DELAY_TIMER);
                context.getLog().info("Transition EXIT_DELAY -> ARMED");
                return armed(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, zonesBeingArmed);
            })
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.EXIT_DELAY))
            .build();
    }

    // ARMED: only sensors in active zones can start the entry-delay countdown.
    private static Behavior<Command> armed(
        ActorContext<Command> context,
        TimerScheduler<Command> timers,
        String correctPin,
        Duration exitDelayDuration,
        Duration entryDelayDuration,
        ActorRef<SirenActor.Command> siren,
        Set<Zone> armedZones
    ) {
        return Behaviors.receive(Command.class)
            .onMessage(PinSubmitted.class, message -> {
                if (correctPin.equals(message.pin())) {
                    context.getLog().info("Transition ARMED -> DISARMED");
                    timers.cancel(ENTRY_DELAY_TIMER);
                    siren.tell(new SirenActor.Deactivate());
                    return disarmed(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, ALL_ZONES);
                }

                return stayInSameStateAfterWrongPin(context, AlarmState.ARMED);
            })
            .onMessage(SensorActivated.class, message -> {
                SensorInfo triggeredSensor = message.sensorInfo();

                if (armedZones.contains(triggeredSensor.zone())) {
                    context.getLog().info(
                        "Transition ARMED -> ENTRY_DELAY due to sensor activation: sensor={}, type={}, zone={}",
                        triggeredSensor.id(),
                        triggeredSensor.type(),
                        triggeredSensor.zone()
                    );
                    timers.startSingleTimer(ENTRY_DELAY_TIMER, new EntryDelayTimeout(), entryDelayDuration);
                    return entryDelay(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, armedZones);
                }

                return ignoreSensorBecauseZoneIsNotArmed(context, triggeredSensor);
            })
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.ARMED))
            .build();
    }

    // ENTRY_DELAY: the user has a short window to disarm before the alarm starts.
    private static Behavior<Command> entryDelay(
        ActorContext<Command> context,
        TimerScheduler<Command> timers,
        String correctPin,
        Duration exitDelayDuration,
        Duration entryDelayDuration,
        ActorRef<SirenActor.Command> siren,
        Set<Zone> armedZones
    ) {
        return Behaviors.receive(Command.class)
            .onMessage(PinSubmitted.class, message -> {
                if (correctPin.equals(message.pin())) {
                    timers.cancel(ENTRY_DELAY_TIMER);
                    context.getLog().info("Transition ENTRY_DELAY -> DISARMED");
                    siren.tell(new SirenActor.Deactivate());
                    return disarmed(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, ALL_ZONES);
                }

                return stayInSameStateAfterWrongPin(context, AlarmState.ENTRY_DELAY);
            })
            .onMessage(SensorActivated.class, message ->
                ignoreSensorBecauseStateIsInactive(context, AlarmState.ENTRY_DELAY, message.sensorInfo()))
            .onMessage(EntryDelayTimeout.class, message -> {
                timers.cancel(ENTRY_DELAY_TIMER);
                context.getLog().info("Transition ENTRY_DELAY -> ALARM");
                siren.tell(new SirenActor.Activate());
                return alarm(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, armedZones);
            })
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.ENTRY_DELAY))
            .build();
    }

    // ALARM: the siren stays active until the correct PIN is submitted.
    private static Behavior<Command> alarm(
        ActorContext<Command> context,
        TimerScheduler<Command> timers,
        String correctPin,
        Duration exitDelayDuration,
        Duration entryDelayDuration,
        ActorRef<SirenActor.Command> siren,
        Set<Zone> armedZones
    ) {
        return Behaviors.receive(Command.class)
            .onMessage(PinSubmitted.class, message -> {
                if (correctPin.equals(message.pin())) {
                    context.getLog().info("Transition ALARM -> DISARMED");
                    siren.tell(new SirenActor.Deactivate());
                    return disarmed(context, timers, correctPin, exitDelayDuration, entryDelayDuration, siren, ALL_ZONES);
                }

                return stayInSameStateAfterWrongPin(context, AlarmState.ALARM);
            })
            .onMessage(SensorActivated.class, message ->
                ignoreSensorBecauseStateIsInactive(context, AlarmState.ALARM, message.sensorInfo()))
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.ALARM))
            .build();
    }

    // Query messages are test/demo helpers and never alter the actor state.
    private static Behavior<Command> replyWithState(ActorRef<StateSnapshot> replyTo, AlarmState state) {
        replyTo.tell(new StateSnapshot(state));
        return Behaviors.same();
    }

    // Invalid PINs are intentionally non-disruptive in every state.
    private static Behavior<Command> stayInSameStateAfterWrongPin(ActorContext<Command> context, AlarmState state) {
        context.getLog().info("Ignoring PIN submission while {} is active", state);
        return Behaviors.same();
    }

    // Sensor events outside ARMED mode are logged but do not change the state machine.
    private static Behavior<Command> ignoreSensorBecauseStateIsInactive(
        ActorContext<Command> context,
        AlarmState state,
        SensorInfo sensor
    ) {
        context.getLog().info(
            "Ignoring sensor activation while {} is active: sensor={}, type={}, zone={}",
            state,
            sensor.id(),
            sensor.type(),
            sensor.zone()
        );
        return Behaviors.same();
    }

    // Partial arming keeps sensors from inactive zones from starting the entry delay.
    private static Behavior<Command> ignoreSensorBecauseZoneIsNotArmed(ActorContext<Command> context, SensorInfo sensor) {
        context.getLog().info(
            "Ignoring sensor activation while ARMED because zone {} is inactive: sensor={}, type={}",
            sensor.zone(),
            sensor.id(),
            sensor.type()
        );
        return Behaviors.same();
    }

    private static void validateCorrectPin(String correctPin) {
        Objects.requireNonNull(correctPin, "correctPin");
        if (correctPin.isBlank()) {
            throw new IllegalArgumentException("correctPin cannot be blank");
        }
    }
}
