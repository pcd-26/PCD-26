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
import java.util.stream.Collectors;

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
            // Pekko will send this timeout message back to this actor after the delay.
            timers.startSingleTimer(EXIT_DELAY_TIMER, new ExitDelayTimeout(), exitDelay);
        }

        void cancelExitDelay() {
            timers.cancel(EXIT_DELAY_TIMER);
        }

        void startEntryDelay() {
            // Pekko will send this timeout message back to this actor after the delay.
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
            // Handles a full arming request from the keypad.
            .onMessage(RequestFullArming.class, message -> {
                if (alarm.correctPin().equals(message.pin())) {
                    // Correct PIN: start the exit phase with every zone enabled.
                    alarm.context().getLog().info(
                        "[ALARM] Full arming accepted. State: DISARMED -> EXIT_DELAY. Active zones: {}",
                        formatZones(ALL_ZONES)
                    );
                    alarm.startExitDelay();
                    return exitDelay(alarm, ALL_ZONES);
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.DISARMED);
            })
            // Handles a partial arming request with the selected active zones.
            .onMessage(RequestPartialArming.class, message -> {
                if (alarm.correctPin().equals(message.pin())) {
                    // Correct PIN: start the exit phase, but only for the selected zones.
                    alarm.context().getLog().info(
                        "[ALARM] Partial arming accepted. State: DISARMED -> EXIT_DELAY. Active zones: {}",
                        formatZones(message.activeZones())
                    );
                    alarm.startExitDelay();
                    return exitDelay(alarm, message.activeZones());
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.DISARMED);
            })
            // Handles a plain PIN submission while no arming mode was chosen.
            .onMessage(PinSubmitted.class, message -> {
                // A PIN alone cannot arm the system: full or partial mode is required.
                alarm.context().getLog().info(
                    "[ALARM] PIN received while DISARMED: no state change. To arm, use 'arm full PIN' or 'arm partial PIN ZONE'."
                );
                return Behaviors.same();
            })
            // Handles sensor events while the system is inactive.
            .onMessage(SensorActivated.class, message ->
                ignoreSensorWithoutStateChange(alarm, AlarmState.DISARMED, message.sensorInfo()))
            // Ignores stale exit-delay timeout messages.
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            // Ignores stale entry-delay timeout messages.
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            // Handles status queries from the root actor.
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.DISARMED))
            .build();
    }

    // EXIT_DELAY: occupants can leave; sensors still do not count as intrusions.
    private static Behavior<Command> exitDelay(AlarmRuntime alarm, Set<Zone> zonesBeingArmed) {
        return Behaviors.receive(Command.class)
            // Handles a PIN submitted before the exit delay expires.
            .onMessage(PinSubmitted.class, message -> {
                if (alarm.accepts(message)) {
                    // During exit delay, the correct PIN cancels arming and returns to disarmed.
                    alarm.cancelExitDelay();
                    alarm.context().getLog().info("[ALARM] Arming cancelled by correct PIN. State: EXIT_DELAY -> DISARMED.");
                    alarm.deactivateSiren();
                    return disarmed(alarm);
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.EXIT_DELAY);
            })
            // Handles sensor events while occupants are still leaving.
            .onMessage(SensorActivated.class, message ->
                ignoreSensorWithoutStateChange(alarm, AlarmState.EXIT_DELAY, message.sensorInfo()))
            // Handles the automatic end of the exit delay.
            .onMessage(ExitDelayTimeout.class, message -> {
                // Once the exit timer expires, sensors in active zones become relevant.
                alarm.cancelExitDelay();
                alarm.context().getLog().info(
                    "[ALARM] Exit delay expired. State: EXIT_DELAY -> ARMED. Active zones: {}",
                    formatZones(zonesBeingArmed)
                );
                return armed(alarm, zonesBeingArmed);
            })
            // Ignores stale entry-delay timeout messages.
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            // Handles status queries from the root actor.
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.EXIT_DELAY))
            .build();
    }

    // ARMED: only sensors in active zones can start the entry-delay countdown.
    private static Behavior<Command> armed(AlarmRuntime alarm, Set<Zone> armedZones) {
        return Behaviors.receive(Command.class)
            // Handles a PIN submitted while the system is fully armed.
            .onMessage(PinSubmitted.class, message -> {
                if (alarm.accepts(message)) {
                    // The correct PIN can disarm directly, even before any sensor fires.
                    alarm.cancelEntryDelay();
                    alarm.context().getLog().info("[ALARM] System disarmed by correct PIN. State: ARMED -> DISARMED.");
                    alarm.deactivateSiren();
                    return disarmed(alarm);
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.ARMED);
            })
            // Handles sensor events and checks whether their zone is active.
            .onMessage(SensorActivated.class, message -> {
                SensorInfo sensor = message.sensorInfo();

                if (armedZones.contains(sensor.zone())) {
                    // Sensor in an active zone: start entry delay instead of the siren.
                    alarm.context().getLog().info(
                        "[ALARM] Intrusion detected in active zone. State: ARMED -> ENTRY_DELAY. Sensor={}, type={}, zone={}.",
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
            // Ignores stale exit-delay timeout messages.
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            // Ignores stale entry-delay timeout messages.
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            // Handles status queries from the root actor.
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.ARMED))
            .build();
    }

    // ENTRY_DELAY: the user has a short window to disarm before the alarm starts.
    private static Behavior<Command> entryDelay(AlarmRuntime alarm, Set<Zone> armedZones) {
        return Behaviors.receive(Command.class)
            // Handles a PIN submitted before the entry delay expires.
            .onMessage(PinSubmitted.class, message -> {
                if (alarm.accepts(message)) {
                    // The user entered the PIN in time: cancel the entry countdown.
                    alarm.cancelEntryDelay();
                    alarm.context().getLog().info("[ALARM] Correct PIN entered before timeout. State: ENTRY_DELAY -> DISARMED.");
                    alarm.deactivateSiren();
                    return disarmed(alarm);
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.ENTRY_DELAY);
            })
            // Handles extra sensor events while the entry countdown is already running.
            .onMessage(SensorActivated.class, message ->
                ignoreSensorWithoutStateChange(alarm, AlarmState.ENTRY_DELAY, message.sensorInfo()))
            // Handles the automatic end of the entry delay.
            .onMessage(EntryDelayTimeout.class, message -> {
                // No valid PIN arrived in time: enter the emergency state.
                alarm.cancelEntryDelay();
                alarm.context().getLog().info("[ALARM] Entry delay expired without a valid PIN. State: ENTRY_DELAY -> ALARM.");
                alarm.activateSiren();
                return alarmTriggered(alarm, armedZones);
            })
            // Ignores stale exit-delay timeout messages.
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            // Handles status queries from the root actor.
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.ENTRY_DELAY))
            .build();
    }

    // ALARM: the siren stays active until the correct PIN is submitted.
    private static Behavior<Command> alarmTriggered(AlarmRuntime alarm, Set<Zone> armedZones) {
        return Behaviors.receive(Command.class)
            // Handles PIN submissions while the siren is active.
            .onMessage(PinSubmitted.class, message -> {
                if (alarm.accepts(message)) {
                    // In alarm, the only valid exit is the correct PIN, which stops the siren.
                    alarm.context().getLog().info("[ALARM] Correct PIN received. Siren stopped. State: ALARM -> DISARMED.");
                    alarm.deactivateSiren();
                    return disarmed(alarm);
                }

                return stayInSameStateAfterWrongPin(alarm, AlarmState.ALARM);
            })
            // Handles extra sensor events after the alarm has already started.
            .onMessage(SensorActivated.class, message ->
                // Alarm is already active: extra sensors do not change the state.
                ignoreSensorWithoutStateChange(alarm, AlarmState.ALARM, message.sensorInfo()))
            // Ignores stale exit-delay timeout messages.
            .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
            // Ignores stale entry-delay timeout messages.
            .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
            // Handles status queries from the root actor.
            .onMessage(QueryState.class, message -> replyWithState(message.replyTo(), AlarmState.ALARM))
            .build();
    }

    // Helpers

    private static Behavior<Command> replyWithState(ActorRef<StateSnapshot> replyTo, AlarmState state) {
        replyTo.tell(new StateSnapshot(state));
        return Behaviors.same();
    }

    private static Behavior<Command> stayInSameStateAfterWrongPin(AlarmRuntime alarm, AlarmState state) {
        alarm.context().getLog().info(
            "[ALARM] Wrong PIN rejected while state is {}. State unchanged.",
            state
        );
        return Behaviors.same();
    }

    private static Behavior<Command> ignoreSensorWithoutStateChange(
        AlarmRuntime alarm,
        AlarmState state,
        SensorInfo sensor
    ) {
        alarm.context().getLog().info(
            "[ALARM] Sensor event logged, but it does not change the state {}. Sensor={}, type={}, zone={}.",
            state,
            sensor.id(),
            sensor.type(),
            sensor.zone()
        );
        return Behaviors.same();
    }

    private static Behavior<Command> ignoreSensorBecauseZoneIsNotArmed(AlarmRuntime alarm, SensorInfo sensor) {
        alarm.context().getLog().info(
            "[ALARM] Sensor event ignored because its zone is not armed. Zone={}, sensor={}, type={}.",
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

    private static String formatZones(Set<Zone> zones) {
        return zones.stream()
            .sorted()
            .map(Zone::name)
            .collect(Collectors.joining(", ", "[", "]"));
    }
}
