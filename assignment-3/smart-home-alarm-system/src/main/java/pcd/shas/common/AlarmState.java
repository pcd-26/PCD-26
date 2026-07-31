package pcd.shas.common;

/**
 * High-level states of the smart home alarm system.
 */
public enum AlarmState {
    /**
     * System is disarmed and monitoring is inactive.
     */
    DISARMED,

    /**
     * System exit-delay is active, allowing occupants to exit before full arming.
     */
    EXIT_DELAY,

    /**
     * System is armed and actively monitoring configured active zones.
     */
    ARMED,

    /**
     * Intrusion detected in an active zone; entry-delay timer is ticking before sounding alarm.
     */
    ENTRY_DELAY,

    /**
     * Alarm condition triggered; siren is active.
     */
    ALARM
}
