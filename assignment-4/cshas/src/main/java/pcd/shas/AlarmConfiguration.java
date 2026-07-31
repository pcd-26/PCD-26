package pcd.shas;

import com.typesafe.config.Config;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable application configuration for the clustered smart home alarm.
 *
 * <p>The values are loaded from {@code application.conf} and kept outside the
 * actor logic so tests can inject shorter timings without changing the
 * production defaults.</p>
 *
 * @param correctPin the configured alarm PIN
 * @param exitDelay the exit-delay duration
 * @param entryDelay the entry-delay duration
 */
public record AlarmConfiguration(String correctPin, Duration exitDelay, Duration entryDelay) {

    private static final String ROOT_PATH = "shas";

    /**
     * Loads the alarm configuration from the {@code shas} config subtree.
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

        requirePositiveDuration(exitDelay, "exitDelay");
        requirePositiveDuration(entryDelay, "entryDelay");
    }

    private static void requirePositiveDuration(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
