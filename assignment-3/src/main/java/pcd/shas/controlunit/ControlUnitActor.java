package pcd.shas.controlunit;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import pcd.shas.common.SensorInfo;
import pcd.shas.keypad.KeypadActor;
import pcd.shas.keypad.PinSubmitted;
import pcd.shas.sensor.SensorActor;
import pcd.shas.sensor.SensorEvent;
import pcd.shas.siren.AlertDevice;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The central control unit actor for the smart home alarm system.
 * It manages the alarm system states (Disarmed, Exit Delay, Armed, Entry Delay, Alarm)
 * using a finite state machine pattern based on Pekko behaviors.
 */
public class ControlUnitActor extends AbstractBehavior<ControlUnitActor.Command> {

    /**
     * Represents the states of the smart home alarm system.
     */
    public enum AlarmState {
        DISARMED,
        EXIT_DELAY,
        ARMED,
        ENTRY_DELAY,
        ALARM
    }

    /**
     * Interface for all commands accepted by the ControlUnitActor.
     */
    public interface Command {}

    /**
     * Command sent by a sensor when it is triggered.
     *
     * @param sensorInfo details of the triggered sensor
     * @param timestamp  the time the trigger occurred
     */
    public record SensorTriggered(SensorInfo sensorInfo, Instant timestamp) implements Command {}

    /**
     * Command sent by the keypad when a PIN code is entered and submitted.
     *
     * @param pin           the entered PIN code
     * @param selectedZones the zones selected for arming (empty means full arming)
     * @param keypadRef     the reference of the keypad that sent the PIN (for response)
     */
    public record KeypadPinEntered(String pin, Set<String> selectedZones, ActorRef<KeypadActor.Command> keypadRef) implements Command {}

    /**
     * Query to inspect the current state of the control unit (useful for testing and simulators).
     *
     * @param replyTo actor to send the state report to
     */
    public record QueryState(ActorRef<StateReport> replyTo) implements Command {}

    /**
     * Command to request the references of spawned keypad and sensors.
     *
     * @param replyTo actor to send the keypad and sensors report to
     */
    public record GetKeypadAndSensors(ActorRef<KeypadAndSensorsReport> replyTo) implements Command {}

    /**
     * Report containing the references of the spawned keypad and sensors.
     */
    public record KeypadAndSensorsReport(ActorRef<KeypadActor.Command> keypad, Map<String, ActorRef<SensorActor.Command>> sensors) {}

    /**
     * Internal command sent when the Exit Delay timer expires.
     */
    private record ExitDelayTimeout() implements Command {}

    /**
     * Internal command sent when the Entry Delay timer expires.
     */
    private record EntryDelayTimeout() implements Command {}

    /**
     * Report representing the current state of the alarm system.
     *
     * @param state            the current alarm state
     * @param fullyArmed       true if all zones are active, false otherwise
     * @param activeZones      the set of active zones (if not fully armed)
     */
    public record StateReport(AlarmState state, boolean fullyArmed, Set<String> activeZones) {}

    private static final Object EXIT_TIMER_KEY = "ExitDelayTimer";
    private static final Object ENTRY_TIMER_KEY = "EntryDelayTimer";

    private final String correctPin;
    private final Duration exitDelayDuration;
    private final Duration entryDelayDuration;
    private final ActorRef<AlertDevice.Command> siren;
    private final TimerScheduler<Command> timers;

    // References to spawned child actors
    private final ActorRef<KeypadActor.Command> keypad;
    private final Map<String, ActorRef<SensorActor.Command>> sensors;

    // FSM State Data
    private boolean fullyArmed = false;
    private final Set<String> activeZones = new HashSet<>();

    /**
     * Backward-compatible factory method for tests that do not configure physical sensors initially.
     */
    public static Behavior<Command> create(String correctPin, Duration exitDelayDuration, Duration entryDelayDuration, ActorRef<AlertDevice.Command> siren) {
        return create(correctPin, exitDelayDuration, entryDelayDuration, siren, Collections.emptyList());
    }

    /**
     * Factory method to create a ControlUnitActor behavior.
     *
     * @param correctPin         the correct PIN code to authorize arming/disarming/silencing
     * @param exitDelayDuration  the duration of the exit delay
     * @param entryDelayDuration the duration of the entry delay
     * @param siren              reference to the AlertDevice (e.g. SirenActor)
     * @param sensorConfigs      the list of sensor configurations to spawn as children
     * @return the behavior of the ControlUnitActor wrapped with a timer scheduler
     */
    public static Behavior<Command> create(String correctPin, Duration exitDelayDuration, Duration entryDelayDuration, ActorRef<AlertDevice.Command> siren, List<SensorInfo> sensorConfigs) {
        return Behaviors.withTimers(timers ->
                Behaviors.setup(context -> {
                    // 1. Create message adapters to satisfy DIP (Dependency Inversion Principle)
                    // The Control Unit adapts Keypad and Sensor events to its own internal command format.
                    ActorRef<SensorEvent> sensorAdapter = context.messageAdapter(SensorEvent.class,
                            event -> new SensorTriggered(event.info(), event.timestamp()));

                    ActorRef<PinSubmitted> keypadAdapter = context.messageAdapter(PinSubmitted.class,
                            event -> new KeypadPinEntered(event.pin(), event.selectedZones(), event.keypadRef()));

                    // 2. Spawn Keypad child actor
                    ActorRef<KeypadActor.Command> keypadRef = context.spawn(
                            KeypadActor.create(keypadAdapter),
                            "keypad"
                    );

                    // 3. Spawn Sensor child actors
                    Map<String, ActorRef<SensorActor.Command>> sensorsMap = new HashMap<>();
                    for (SensorInfo info : sensorConfigs) {
                        ActorRef<SensorActor.Command> sensorRef = context.spawn(
                                SensorActor.create(info, sensorAdapter),
                                "sensor-" + info.id()
                        );
                        sensorsMap.put(info.id(), sensorRef);
                    }

                    return new ControlUnitActor(context, correctPin, exitDelayDuration, entryDelayDuration, siren, timers, keypadRef, sensorsMap);
                })
        );
    }

    private ControlUnitActor(
            ActorContext<Command> context,
            String correctPin,
            Duration exitDelayDuration,
            Duration entryDelayDuration,
            ActorRef<AlertDevice.Command> siren,
            TimerScheduler<Command> timers,
            ActorRef<KeypadActor.Command> keypad,
            Map<String, ActorRef<SensorActor.Command>> sensors
    ) {
        super(context);
        this.correctPin = correctPin;
        this.exitDelayDuration = exitDelayDuration;
        this.entryDelayDuration = entryDelayDuration;
        this.siren = siren;
        this.timers = timers;
        this.keypad = keypad;
        this.sensors = sensors;
    }

    @Override
    public Receive<Command> createReceive() {
        return disarmedState();
    }

    // ==========================================
    // 1. DISARMED STATE BEHAVIOR
    // ==========================================
    private Receive<Command> disarmedState() {
        return newReceiveBuilder()
                .onMessage(SensorTriggered.class, this::onSensorTriggeredInDisarmed)
                .onMessage(KeypadPinEntered.class, this::onKeypadPinEnteredInDisarmed)
                .onMessage(QueryState.class, cmd -> {
                    cmd.replyTo().tell(new StateReport(AlarmState.DISARMED, false, Collections.emptySet()));
                    return Behaviors.same();
                })
                .onMessage(GetKeypadAndSensors.class, this::onGetKeypadAndSensors)
                .build();
    }

    private Behavior<Command> onSensorTriggeredInDisarmed(SensorTriggered cmd) {
        getContext().getLog().info("Disarmed: Sensor '{}' triggered in zone '{}'. (Ignored)", cmd.sensorInfo().id(), cmd.sensorInfo().zone());
        return Behaviors.same();
    }

    private Behavior<Command> onKeypadPinEnteredInDisarmed(KeypadPinEntered cmd) {
        if (correctPin.equals(cmd.pin())) {
            cmd.keypadRef().tell(new KeypadActor.PinAccepted());
            
            // Set up zones for arming
            if (cmd.selectedZones().isEmpty()) {
                this.fullyArmed = true;
                this.activeZones.clear();
                getContext().getLog().info("Correct PIN entered. Starting EXIT DELAY for FULL arming (all zones active).");
                System.out.println("System: [EXIT DELAY STARTING] - FULL ARMING in progress. Exit house now!");
            } else {
                this.fullyArmed = false;
                this.activeZones.clear();
                this.activeZones.addAll(cmd.selectedZones());
                getContext().getLog().info("Correct PIN entered. Starting EXIT DELAY for PARTIAL arming (Zones: {}).", activeZones);
                System.out.println("System: [EXIT DELAY STARTING] - PARTIAL ARMING in progress (Zones: " + activeZones + "). Exit house now!");
            }

            // Start exit delay timer
            timers.startSingleTimer(EXIT_TIMER_KEY, new ExitDelayTimeout(), exitDelayDuration);
            return Behaviors.receive(Command.class)
                    .onMessage(SensorTriggered.class, this::onSensorTriggeredInExitDelay)
                    .onMessage(KeypadPinEntered.class, this::onKeypadPinEnteredInExitDelay)
                    .onMessage(ExitDelayTimeout.class, this::onExitDelayTimeout)
                    .onMessage(QueryState.class, this::onQueryInExitDelay)
                    .onMessage(GetKeypadAndSensors.class, this::onGetKeypadAndSensors)
                    .build();
        } else {
            cmd.keypadRef().tell(new KeypadActor.PinRejected());
            getContext().getLog().warn("Disarmed: Invalid PIN entered.");
            return Behaviors.same();
        }
    }

    // ==========================================
    // 2. EXIT DELAY STATE BEHAVIOR
    // ==========================================
    private Behavior<Command> onSensorTriggeredInExitDelay(SensorTriggered cmd) {
        getContext().getLog().info("Exit Delay: Sensor '{}' triggered in zone '{}'. (Ignored)", cmd.sensorInfo().id(), cmd.sensorInfo().zone());
        return Behaviors.same();
    }

    private Behavior<Command> onKeypadPinEnteredInExitDelay(KeypadPinEntered cmd) {
        if (correctPin.equals(cmd.pin())) {
            timers.cancel(EXIT_TIMER_KEY);
            cmd.keypadRef().tell(new KeypadActor.PinAccepted());
            getContext().getLog().info("Exit Delay: Correct PIN entered. Disarming system and canceling arming sequence.");
            System.out.println("System: [DISARMED] - Arming canceled by user.");
            resetStateData();
            return disarmedState();
        } else {
            cmd.keypadRef().tell(new KeypadActor.PinRejected());
            getContext().getLog().warn("Exit Delay: Invalid PIN entered during exit sequence.");
            return Behaviors.same();
        }
    }

    private Behavior<Command> onExitDelayTimeout(ExitDelayTimeout cmd) {
        getContext().getLog().info("Exit Delay: Timeout reached. Transitioning to ARMED state.");
        if (fullyArmed) {
            System.out.println("System: [ARMED] - System is FULLY ARMED. Active zones: ALL");
        } else {
            System.out.println("System: [ARMED] - System is PARTIALLY ARMED. Active zones: " + activeZones);
        }
        return Behaviors.receive(Command.class)
                .onMessage(SensorTriggered.class, this::onSensorTriggeredInArmed)
                .onMessage(KeypadPinEntered.class, this::onKeypadPinEnteredInArmed)
                .onMessage(QueryState.class, this::onQueryInArmed)
                .onMessage(GetKeypadAndSensors.class, this::onGetKeypadAndSensors)
                .build();
    }

    private Behavior<Command> onQueryInExitDelay(QueryState cmd) {
        cmd.replyTo().tell(new StateReport(AlarmState.EXIT_DELAY, fullyArmed, new HashSet<>(activeZones)));
        return Behaviors.same();
    }

    // ==========================================
    // 3. ARMED STATE BEHAVIOR
    // ==========================================
    private Behavior<Command> onSensorTriggeredInArmed(SensorTriggered cmd) {
        String zone = cmd.sensorInfo().zone();
        boolean isZoneActive = fullyArmed || activeZones.contains(zone);
        if (isZoneActive) {
            getContext().getLog().warn("Armed: Sensor '{}' triggered in active zone '{}'. Transitioning to ENTRY DELAY.", cmd.sensorInfo().id(), zone);
            System.out.println("System: [INTRUSION DETECTED] by sensor '" + cmd.sensorInfo().id() + "' in active zone '" + zone + "'!");
            System.out.println("System: [ENTRY DELAY STARTING] - Enter PIN within " + entryDelayDuration.toSeconds() + " seconds to disarm!");

            // Start entry delay timer
            timers.startSingleTimer(ENTRY_TIMER_KEY, new EntryDelayTimeout(), entryDelayDuration);
            return Behaviors.receive(Command.class)
                    .onMessage(SensorTriggered.class, this::onSensorTriggeredInEntryDelay)
                    .onMessage(KeypadPinEntered.class, this::onKeypadPinEnteredInEntryDelay)
                    .onMessage(EntryDelayTimeout.class, this::onEntryDelayTimeout)
                    .onMessage(QueryState.class, this::onQueryInEntryDelay)
                    .onMessage(GetKeypadAndSensors.class, this::onGetKeypadAndSensors)
                    .build();
        } else {
            getContext().getLog().info("Armed: Sensor '{}' triggered in inactive zone '{}'. (Ignored)", cmd.sensorInfo().id(), zone);
            return Behaviors.same();
        }
    }

    private Behavior<Command> onKeypadPinEnteredInArmed(KeypadPinEntered cmd) {
        if (correctPin.equals(cmd.pin())) {
            cmd.keypadRef().tell(new KeypadActor.PinAccepted());
            getContext().getLog().info("Armed: Correct PIN entered. Disarming system.");
            System.out.println("System: [DISARMED]");
            resetStateData();
            return disarmedState();
        } else {
            cmd.keypadRef().tell(new KeypadActor.PinRejected());
            getContext().getLog().warn("Armed: Invalid PIN entered.");
            return Behaviors.same();
        }
    }

    private Behavior<Command> onQueryInArmed(QueryState cmd) {
        cmd.replyTo().tell(new StateReport(AlarmState.ARMED, fullyArmed, new HashSet<>(activeZones)));
        return Behaviors.same();
    }

    // ==========================================
    // 4. ENTRY DELAY STATE BEHAVIOR
    // ==========================================
    private Behavior<Command> onSensorTriggeredInEntryDelay(SensorTriggered cmd) {
        // Already in entry delay, ignore additional sensor events
        return Behaviors.same();
    }

    private Behavior<Command> onKeypadPinEnteredInEntryDelay(KeypadPinEntered cmd) {
        if (correctPin.equals(cmd.pin())) {
            timers.cancel(ENTRY_TIMER_KEY);
            cmd.keypadRef().tell(new KeypadActor.PinAccepted());
            getContext().getLog().info("Entry Delay: Correct PIN entered. Disarming system and silencing any alarms.");
            System.out.println("System: [DISARMED] - PIN authenticated successfully.");
            resetStateData();
            return disarmedState();
        } else {
            cmd.keypadRef().tell(new KeypadActor.PinRejected());
            getContext().getLog().warn("Entry Delay: Invalid PIN entered.");
            return Behaviors.same();
        }
    }

    private Behavior<Command> onEntryDelayTimeout(EntryDelayTimeout cmd) {
        getContext().getLog().error("Entry Delay: Timeout reached. Transitioning to ALARM state!");
        System.out.println("System: [TIMEOUT] - Entry delay elapsed. Triggering emergency alarm!");
        siren.tell(new AlertDevice.Activate());
        return Behaviors.receive(Command.class)
                .onMessage(SensorTriggered.class, this::onSensorTriggeredInAlarm)
                .onMessage(KeypadPinEntered.class, this::onKeypadPinEnteredInAlarm)
                .onMessage(QueryState.class, this::onQueryInAlarm)
                .onMessage(GetKeypadAndSensors.class, this::onGetKeypadAndSensors)
                .build();
    }

    private Behavior<Command> onQueryInEntryDelay(QueryState cmd) {
        cmd.replyTo().tell(new StateReport(AlarmState.ENTRY_DELAY, fullyArmed, new HashSet<>(activeZones)));
        return Behaviors.same();
    }

    // ==========================================
    // 5. ALARM STATE BEHAVIOR
    // ==========================================
    private Behavior<Command> onSensorTriggeredInAlarm(SensorTriggered cmd) {
        // System is already in alarm, ignore additional triggers
        return Behaviors.same();
    }

    private Behavior<Command> onKeypadPinEnteredInAlarm(KeypadPinEntered cmd) {
        if (correctPin.equals(cmd.pin())) {
            siren.tell(new AlertDevice.Deactivate());
            cmd.keypadRef().tell(new KeypadActor.PinAccepted());
            getContext().getLog().info("Alarm: Correct PIN entered. Disarming system and deactivating siren.");
            System.out.println("System: [DISARMED] - Siren silenced and alarm turned off.");
            resetStateData();
            return disarmedState();
        } else {
            cmd.keypadRef().tell(new KeypadActor.PinRejected());
            getContext().getLog().warn("Alarm: Invalid PIN entered.");
            return Behaviors.same();
        }
    }

    private Behavior<Command> onQueryInAlarm(QueryState cmd) {
        cmd.replyTo().tell(new StateReport(AlarmState.ALARM, fullyArmed, new HashSet<>(activeZones)));
        return Behaviors.same();
    }

    // ==========================================
    // UTILITY METHODS
    // ==========================================
    private void resetStateData() {
        this.fullyArmed = false;
        this.activeZones.clear();
    }

    private Behavior<Command> onGetKeypadAndSensors(GetKeypadAndSensors cmd) {
        cmd.replyTo().tell(new KeypadAndSensorsReport(keypad, sensors));
        return Behaviors.same();
    }
}
