package pcd.shas;

import com.typesafe.config.Config;

import java.time.Duration;
import java.util.Objects;

public record AlarmConfiguration(String correctPin, Duration exitDelay, Duration entryDelay) {

    private static final String CONFIG_ROOT = "shas";

    // Reads only the alarm settings used by the actor system.
    public static AlarmConfiguration from(Config config) {
        Objects.requireNonNull(config, "config");

        Config alarmConfig = config.getConfig(CONFIG_ROOT);
        return new AlarmConfiguration(
            alarmConfig.getString("correctPin"),
            alarmConfig.getDuration("exitDelay"),
            alarmConfig.getDuration("entryDelay")
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
