package pcd.shas.siren;

/**
 * An abstraction representing an alert device in the alarm system,
 * such as a siren or a silent alarm.
 */
public interface AlertDevice {

    /**
     * Interface for all commands accepted by an alert device.
     */
    interface Command {}

    /**
     * Command to activate the alert device.
     */
    record Activate() implements Command {}

    /**
     * Command to deactivate the alert device.
     */
    record Deactivate() implements Command {}
}
