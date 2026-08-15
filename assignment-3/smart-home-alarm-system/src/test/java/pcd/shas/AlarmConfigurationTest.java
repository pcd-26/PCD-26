package pcd.shas;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlarmConfigurationTest {

    @Test
    void loadsDefaultValuesFromApplicationConfig() {
        AlarmConfiguration configuration = AlarmConfiguration.from(ConfigFactory.load());

        assertEquals("1234", configuration.correctPin());
        assertEquals(Duration.ofMillis(300), configuration.exitDelay());
        assertEquals(Duration.ofMillis(300), configuration.entryDelay());
    }

    @Test
    void allowsTestsToOverrideConfigurationValues() {
        AlarmConfiguration configuration = AlarmConfiguration.from(
                ConfigFactory.parseString(
                        """
                                shas {
                                  correctPin = "9999"
                                  exitDelay = 1 second
                                  entryDelay = 2 seconds
                                }
                                """
                )
        );

        assertEquals("9999", configuration.correctPin());
        assertEquals(Duration.ofSeconds(1), configuration.exitDelay());
        assertEquals(Duration.ofSeconds(2), configuration.entryDelay());
    }

    @Test
    void rejectsNonPositiveDurations() {
        assertThrows(IllegalArgumentException.class, () ->
                AlarmConfiguration.from(ConfigFactory.parseString(
                        """
                                shas {
                                  correctPin = "1234"
                                  exitDelay = 0 seconds
                                  entryDelay = 1 second
                                }
                                """
                ))
        );

        assertThrows(IllegalArgumentException.class, () ->
                AlarmConfiguration.from(ConfigFactory.parseString(
                        """
                                shas {
                                  correctPin = "1234"
                                  exitDelay = 1 second
                                  entryDelay = -1 second
                                }
                                """
                ))
        );
    }
}
