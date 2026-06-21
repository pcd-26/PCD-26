package pcd.poool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TaskBasedPooolTest {

    @Test
    void parseWorkerCountUsesADefaultWhenNoArgumentsAreProvided() {
        int workers = TaskBasedPoool.parseWorkerCount(new String[0]);

        assertEquals(Math.max(1, Runtime.getRuntime().availableProcessors() - 1), workers);
    }

    @Test
    void parseWorkerCountAcceptsAnExplicitPositiveValue() {
        int workers = TaskBasedPoool.parseWorkerCount(new String[] {"3"});

        assertEquals(3, workers);
    }

    @Test
    void parseWorkerCountRejectsNonPositiveValues() {
        assertThrows(IllegalArgumentException.class, () -> TaskBasedPoool.parseWorkerCount(new String[] {"0"}));
    }

    @Test
    void taskBasedConfigCarriesTheSelectedWorkerCount() {
        var config = TaskBasedPoool.taskBasedConfig(new String[] {"5"});

        assertEquals(5, config.physicsWorkerCount());
    }
}
