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

    private static final String ROOT_PATH = "shas";

    /**
     * Builds the configuration from the standard application config tree.
     *
     * @param config the loaded application config
     * @return the parsed alarm configuration
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

    public AlarmConfiguration {
        Objects.requireNonNull(correctPin, "correctPin");
        if (correctPin.isBlank()) {
            throw new IllegalArgumentException("correctPin cannot be blank");
        }

        exitDelay = requirePositiveDuration(exitDelay, "exitDelay");
        entryDelay = requirePositiveDuration(entryDelay, "entryDelay");
    }

    private static Duration requirePositiveDuration(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return duration;
    }
}
