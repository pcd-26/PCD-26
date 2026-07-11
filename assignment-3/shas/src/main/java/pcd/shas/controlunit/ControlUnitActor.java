package pcd.shas.controlunit;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import pcd.shas.common.AlarmState;
import pcd.shas.common.SensorInfo;

import java.time.Duration;
import java.util.Objects;

/**
 * Control unit for the smart home alarm system.
 *
 * <p>The actor owns the configured PIN and the current alarm state. It is
 * implemented as an immutable typed-state machine with internal timers for the
 * delay transitions.</p>
 */
public final class ControlUnitActor {

    private static final Object EXIT_DELAY_TIMER_KEY = "exit-delay";
    private static final Object ENTRY_DELAY_TIMER_KEY = "entry-delay";

    private ControlUnitActor() {
        // Utility class.
    }

    /**
     * Root protocol for the control unit.
     */
    public interface Command {}

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
     * Sensor activation event.
     *
     * @param sensorInfo the activated sensor
     */
    public record SensorActivated(SensorInfo sensorInfo) implements Command {
        public SensorActivated {
            Objects.requireNonNull(sensorInfo, "sensorInfo");
        }
    }

    /**
     * Query for the current alarm state, used by tests.
     *
     * @param replyTo actor that should receive the state snapshot
     */
    public record QueryState(ActorRef<StateSnapshot> replyTo) implements Command {
        public QueryState {
            Objects.requireNonNull(replyTo, "replyTo");
        }
    }

    /**
     * Snapshot of the current alarm state.
     *
     * @param state the current alarm state
     */
    public record StateSnapshot(AlarmState state) {
        public StateSnapshot {
            Objects.requireNonNull(state, "state");
        }
    }

    private record ExitDelayTimeout() implements Command {}

    private record EntryDelayTimeout() implements Command {}

    /**
     * Creates the control unit with default delay durations.
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

        return Behaviors.setup(context ->
                Behaviors.withTimers(timers ->
                        disarmed(context, timers, configuredPin, exitDelayDuration, entryDelayDuration)
                )
        );
    }

    private static Behavior<Command> disarmed(
            ActorContext<Command> context,
            TimerScheduler<Command> timers,
            String configuredPin,
            Duration exitDelayDuration,
            Duration entryDelayDuration
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        context.getLog().info("Transition DISARMED -> EXIT_DELAY");
                        timers.startSingleTimer(EXIT_DELAY_TIMER_KEY, new ExitDelayTimeout(), exitDelayDuration);
                        return exitDelay(context, timers, configuredPin, exitDelayDuration, entryDelayDuration);
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
                .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
                .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
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
            Duration entryDelayDuration
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        timers.cancel(EXIT_DELAY_TIMER_KEY);
                        context.getLog().info("Transition EXIT_DELAY -> DISARMED");
                        return disarmed(context, timers, configuredPin, exitDelayDuration, entryDelayDuration);
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
                    timers.cancel(EXIT_DELAY_TIMER_KEY);
                    context.getLog().info("Transition EXIT_DELAY -> ARMED");
                    return armed(context, timers, configuredPin, exitDelayDuration, entryDelayDuration);
                })
                .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
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
            Duration entryDelayDuration
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        context.getLog().info("Transition ARMED -> DISARMED");
                        timers.cancel(ENTRY_DELAY_TIMER_KEY);
                        return disarmed(context, timers, configuredPin, exitDelayDuration, entryDelayDuration);
                    }

                    context.getLog().info("Ignoring PIN submission while ARMED");
                    return Behaviors.same();
                })
                .onMessage(SensorActivated.class, message -> {
                    context.getLog().info(
                            "Transition ARMED -> ENTRY_DELAY due to sensor activation: sensor={}, type={}, zone={}",
                            message.sensorInfo().id(),
                            message.sensorInfo().type(),
                            message.sensorInfo().zone()
                    );
                    timers.startSingleTimer(ENTRY_DELAY_TIMER_KEY, new EntryDelayTimeout(), entryDelayDuration);
                    return entryDelay(context, timers, configuredPin, exitDelayDuration, entryDelayDuration);
                })
                .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
                .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
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
            Duration entryDelayDuration
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        timers.cancel(ENTRY_DELAY_TIMER_KEY);
                        context.getLog().info("Transition ENTRY_DELAY -> DISARMED");
                        return disarmed(context, timers, configuredPin, exitDelayDuration, entryDelayDuration);
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
                    timers.cancel(ENTRY_DELAY_TIMER_KEY);
                    context.getLog().info("Transition ENTRY_DELAY -> ALARM");
                    return alarm(context, timers, configuredPin, exitDelayDuration, entryDelayDuration);
                })
                .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
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
            Duration entryDelayDuration
    ) {
        return Behaviors.receive(Command.class)
                .onMessage(PinSubmitted.class, message -> {
                    if (configuredPin.equals(message.pin())) {
                        context.getLog().info("Transition ALARM -> DISARMED");
                        return disarmed(context, timers, configuredPin, exitDelayDuration, entryDelayDuration);
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
                .onMessage(ExitDelayTimeout.class, message -> Behaviors.same())
                .onMessage(EntryDelayTimeout.class, message -> Behaviors.same())
                .onMessage(QueryState.class, message -> {
                    message.replyTo().tell(new StateSnapshot(AlarmState.ALARM));
                    return Behaviors.same();
                })
                .build();
    }

    private static void validateConfiguredPin(String configuredPin) {
        Objects.requireNonNull(configuredPin, "configuredPin");
        if (configuredPin.isBlank()) {
            throw new IllegalArgumentException("configuredPin cannot be blank");
        }
    }
}
