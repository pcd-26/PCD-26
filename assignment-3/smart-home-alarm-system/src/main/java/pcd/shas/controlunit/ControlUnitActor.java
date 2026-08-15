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

    // Protocol

    public interface Command {}

    public record PinSubmitted(String pin) implements Command {
        public PinSubmitted {
            Objects.requireNonNull(pin, "pin");
        }
    }

    public record RequestFullArming(String pin) implements Command {
        public RequestFullArming {
            Objects.requireNonNull(pin, "pin");
        }
    }

    public record RequestPartialArming(String pin, Set<Zone> activeZones) implements Command {
        public RequestPartialArming {
            Objects.requireNonNull(pin, "pin");
            Objects.requireNonNull(activeZones, "activeZones");
            if (activeZones.isEmpty()) {
                throw new IllegalArgumentException("activeZones cannot be empty");
            }
            activeZones = Set.copyOf(activeZones);
        }
    }

    public record SensorActivated(SensorInfo sensorInfo) implements Command {
        public SensorActivated {
            Objects.requireNonNull(sensorInfo, "sensorInfo");
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

    // Creation

    public static Behavior<Command> create(String correctPin, ActorRef<SirenActor.Command> siren) {
        return create(correctPin, Duration.ofSeconds(5), Duration.ofSeconds(5), siren);
    }

    public static Behavior<Command> create(
        String correctPin,
        Duration exitDelay,
        Duration entryDelay,
        ActorRef<SirenActor.Command> siren
    ) {
        validateCorrectPin(correctPin);
        Objects.requireNonNull(exitDelay, "exitDelay");
        Objects.requireNonNull(entryDelay, "entryDelay");
        Objects.requireNonNull(siren, "siren");

        return Behaviors.setup(context -> Behaviors.withTimers(timers -> {
            var alarm = new AlarmRuntime(context, timers, correctPin, exitDelay, entryDelay, siren);
            return disarmed(alarm);
        }));
    }

    // Runtime context

    // Shared dependencies and operations used by every state of the actor.
    private record AlarmRuntime(
        ActorContext<Command> context,
        TimerScheduler<Command> timers,
        String correctPin,
        Duration exitDelay,
        Duration entryDelay,
        ActorRef<SirenActor.Command> siren
    ) {
        AlarmRuntime {
            validateCorrectPin(correctPin);
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(timers, "timers");
            Objects.requireNonNull(exitDelay, "exitDelay");
            Objects.requireNonNull(entryDelay, "entryDelay");
            Objects.requireNonNull(siren, "siren");
        }

        boolean accepts(PinSubmitted pin) {
            return correctPin.equals(pin.pin());
        }

        void startExitDelay() {
            timers.startSingleTimer(EXIT_DELAY_TIMER, new ExitDelayTimeout(), exitDelay);
        }

        void cancelExitDelay() {
            timers.cancel(EXIT_DELAY_TIMER);
        }

        void startEntryDelay() {
            timers.startSingleTimer(ENTRY_DELAY_TIMER, new EntryDelayTimeout(), entryDelay);
        }

        void cancelEntryDelay() {
            timers.cancel(ENTRY_DELAY_TIMER);
        }

        void activateSiren() {
            siren.tell(new SirenActor.Activate());
        }

        void deactivateSiren() {
            siren.tell(new SirenActor.Deactivate());
        }
    }

    // State behaviors

    // DISARMED: sensors are ignored; a valid arming request starts the exit delay.
    private static Behavior<Command> disarmed(AlarmRuntime alarm) {
        return Behaviors.receive(Command.class)
            .onMessage(RequestFullArming.class, message -> {
                if (alarm.correctPin().equals(message.pin())) {
                    // Correct PIN: start the exit phase with every zone enabled.
                    alarm.context().getLog().info("Transition DISARMED -> EXIT_DELAY with all zones active");
                    alarm.startExitDelay();
                    return exitDelay(alarm, ALL_ZONES);
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.DISARMED);
            })
            .onMessage(RequestPartialArming.class, message -> {
                if (alarm.correctPin().equals(message.pin())) {
                    // Correct PIN: start the exit phase, but only for the selected zones.
                    alarm.context().getLog().info("Transition DISARMED -> EXIT_DELAY with zones {} active", message.activeZones());
                    alarm.startExitDelay();
                    return exitDelay(alarm, message.activeZones());
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.DISARMED);
            })
            .onMessage(PinSubmitted.class, message -> {
                // A PIN alone cannot arm the system: full or partial mode is required.
                alarm.context().getLog().info("PIN submitted while DISARMED, but arming mode is required");
                return Behaviors.same();
            })
            .onMessage(SensorActivated.class, message ->
                ignoreSensorBecauseStateIsInactive(alarm, AlarmState.DISARMED, message.sensorInfo()))
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.DISARMED))
            .build();
    }

    // EXIT_DELAY: occupants can leave; sensors still do not count as intrusions.
    private static Behavior<Command> exitDelay(AlarmRuntime alarm, Set<Zone> zonesBeingArmed) {
        return Behaviors.receive(Command.class)
            .onMessage(PinSubmitted.class, message -> {
                if (alarm.accepts(message)) {
                    // During exit delay, the correct PIN cancels arming and returns to disarmed.
                    alarm.cancelExitDelay();
                    alarm.context().getLog().info("Transition EXIT_DELAY -> DISARMED");
                    alarm.deactivateSiren();
                    return disarmed(alarm);
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.EXIT_DELAY);
            })
            .onMessage(SensorActivated.class, message ->
                ignoreSensorBecauseStateIsInactive(alarm, AlarmState.EXIT_DELAY, message.sensorInfo()))
            .onMessage(ExitDelayTimeout.class, message -> {
                // Once the exit timer expires, sensors in active zones become relevant.
                alarm.cancelExitDelay();
                alarm.context().getLog().info("Transition EXIT_DELAY -> ARMED");
                return armed(alarm, zonesBeingArmed);
            })
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.EXIT_DELAY))
            .build();
    }

    // ARMED: only sensors in active zones can start the entry-delay countdown.
    private static Behavior<Command> armed(AlarmRuntime alarm, Set<Zone> armedZones) {
        return Behaviors.receive(Command.class)
            .onMessage(PinSubmitted.class, message -> {
                if (alarm.accepts(message)) {
                    // The correct PIN can disarm directly, even before any sensor fires.
                    alarm.cancelEntryDelay();
                    alarm.context().getLog().info("Transition ARMED -> DISARMED");
                    alarm.deactivateSiren();
                    return disarmed(alarm);
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.ARMED);
            })
            .onMessage(SensorActivated.class, message -> {
                SensorInfo sensor = message.sensorInfo();

                if (armedZones.contains(sensor.zone())) {
                    // Sensor in an active zone: start entry delay instead of the siren.
                    alarm.context().getLog().info(
                        "Transition ARMED -> ENTRY_DELAY due to sensor activation: sensor={}, type={}, zone={}",
                        sensor.id(),
                        sensor.type(),
                        sensor.zone()
                    );
                    alarm.startEntryDelay();
                    return entryDelay(alarm, armedZones);
                }

                // Sensor in an inactive zone: real event, but irrelevant for the alarm.
                return ignoreSensorBecauseZoneIsNotArmed(alarm, sensor);
            })
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.ARMED))
            .build();
    }

    // ENTRY_DELAY: the user has a short window to disarm before the alarm starts.
    private static Behavior<Command> entryDelay(AlarmRuntime alarm, Set<Zone> armedZones) {
        return Behaviors.receive(Command.class)
            .onMessage(PinSubmitted.class, message -> {
                if (alarm.accepts(message)) {
                    // The user entered the PIN in time: cancel the entry countdown.
                    alarm.cancelEntryDelay();
                    alarm.context().getLog().info("Transition ENTRY_DELAY -> DISARMED");
                    alarm.deactivateSiren();
                    return disarmed(alarm);
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.ENTRY_DELAY);
            })
            .onMessage(SensorActivated.class, message ->
                ignoreSensorBecauseStateIsInactive(alarm, AlarmState.ENTRY_DELAY, message.sensorInfo()))
            .onMessage(EntryDelayTimeout.class, message -> {
                // No valid PIN arrived in time: enter the emergency state.
                alarm.cancelEntryDelay();
                alarm.context().getLog().info("Transition ENTRY_DELAY -> ALARM");
                alarm.activateSiren();
                return alarmTriggered(alarm, armedZones);
            })
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.ENTRY_DELAY))
            .build();
    }

    // ALARM: the siren stays active until the correct PIN is submitted.
    private static Behavior<Command> alarmTriggered(AlarmRuntime alarm, Set<Zone> armedZones) {
        return Behaviors.receive(Command.class)
            .onMessage(PinSubmitted.class, message -> {
                if (alarm.accepts(message)) {
                    // In alarm, the only valid exit is the correct PIN, which stops the siren.
                    alarm.context().getLog().info("Transition ALARM -> DISARMED");
                    alarm.deactivateSiren();
                    return disarmed(alarm);
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.ALARM);
            })
            .onMessage(SensorActivated.class, message ->
                // Alarm is already active: extra sensors do not change the state.
                ignoreSensorBecauseStateIsInactive(alarm, AlarmState.ALARM, message.sensorInfo()))
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.ALARM))
            .build();
    }

    // Helpers

    private static Behavior<Command> replyWithState(ActorRef<StateSnapshot> replyTo, AlarmState state) {
        replyTo.tell(new StateSnapshot(state));
        return Behaviors.same();
    }

    private static Behavior<Command> stayInSameStateAfterWrongPin(AlarmRuntime alarm, AlarmState state) {
        alarm.context().getLog().info("Ignoring PIN submission while {} is active", state);
        return Behaviors.same();
    }

    private static Behavior<Command> ignoreSensorBecauseStateIsInactive(
        AlarmRuntime alarm,
        AlarmState state,
        SensorInfo sensor
    ) {
        alarm.context().getLog().info(
            "Ignoring sensor activation while {} is active: sensor={}, type={}, zone={}",
            state,
            sensor.id(),
            sensor.type(),
            sensor.zone()
        );
        return Behaviors.same();
    }

    private static Behavior<Command> ignoreSensorBecauseZoneIsNotArmed(AlarmRuntime alarm, SensorInfo sensor) {
        alarm.context().getLog().info(
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
