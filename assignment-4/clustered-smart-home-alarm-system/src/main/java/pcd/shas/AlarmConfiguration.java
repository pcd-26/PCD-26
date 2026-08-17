package pcd.shas;

import com.typesafe.config.Config;

import java.time.Duration;
import java.util.Objects;

// Immutable alarm settings loaded from application.conf.
public record AlarmConfiguration(String correctPin, Duration exitDelay, Duration entryDelay) {

    private static final String ROOT_PATH = "shas";

    // Loads the alarm configuration from the shas config subtree.
    public static AlarmConfiguration from(Config config) {
        Objects.requireNonNull(config, "config");

        // Keep actor logic independent from configuration parsing.
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

        // Timers must be real positive delays.
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
