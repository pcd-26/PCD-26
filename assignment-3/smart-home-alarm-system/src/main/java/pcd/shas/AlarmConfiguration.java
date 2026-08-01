package pcd.shas;

import com.typesafe.config.Config;

import java.time.Duration;
import java.util.Objects;

/**
 * Application-level configuration for the smart home alarm.
 *
 * <p>The configuration is loaded through the standard Typesafe Config mechanism
 * and kept separate from actor logic so tests can provide custom values without
 * changing production defaults.</p>
 *
 * @param correctPin the configured alarm PIN
 * @param exitDelay the exit-delay duration
 * @param entryDelay the entry-delay duration
 */
public record AlarmConfiguration(String correctPin, Duration exitDelay, Duration entryDelay) {

    /**
     * Configuration root path prefix in Typesafe Config tree.
     */
    private static final String ROOT_PATH = "shas";

    /**
     * Builds the configuration from the standard application config tree.
     *
     * @param config the loaded application config
     * @return the parsed alarm configuration
     * @throws NullPointerException if {@code config} is null
     */
    public static AlarmConfiguration from(Config config) {
        Objects.requireNonNull(config, "config");

        Config shasConfig = config.getConfig(ROOT_PATH);
        return new AlarmConfiguration(
            shasConfig.getString("correctPin"),
            shasConfig.getDuration("exitDelay"),
            shasConfig.getDuration("entryDelay")
        );
    }

    /**
     * Compact constructor enforcing validation rules for configuration fields.
     *
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if {@code correctPin} is blank or delays are non-positive
     */
    public AlarmConfiguration {
        Objects.requireNonNull(correctPin, "correctPin");
        if (correctPin.isBlank()) {
            throw new IllegalArgumentException("correctPin cannot be blank");
        }

        requirePositiveDuration(exitDelay, "exitDelay");
        requirePositiveDuration(entryDelay, "entryDelay");
    }

    /**
     * Validates that the provided duration parameter is strictly positive.
     *
     * @param duration the duration value to check
     * @param fieldName the field name used in exception messages
     * @throws NullPointerException if {@code duration} is null
     * @throws IllegalArgumentException if {@code duration} is zero or negative
     */
    private static void requirePositiveDuration(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
