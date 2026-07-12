package pcd.shas.common;

/**
 * High-level states of the smart home alarm system, extended with a RECOVERY state for clustered operation.
 */
public enum AlarmState {
    /**
     * System is disarmed, sensors do not trigger alarms.
     */
    DISARMED,

    /**
     * Delay before system becomes armed, allowing exit.
     */
    EXIT_DELAY,

    /**
     * System is armed, sensors trigger alarms (after optional entry delay).
     */
    ARMED,

    /**
     * Delay before alarm sounds, allowing user to submit correct PIN.
     */
    ENTRY_DELAY,

    /**
     * System is in alarm state, siren is active.
     */
    ALARM,

    /**
     * Safe recovery state entered when control unit restarts or recreates.
     * Prevents assuming armed or disarmed state.
     */
    RECOVERY
}
