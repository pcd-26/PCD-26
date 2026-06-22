package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuiResponsivenessMonitorTest {

    @Test
    void snapshotTracksRequestLatencyAndEdtDelay() throws Exception {
        var monitor = new GuiResponsivenessMonitor(0L);

        long first = monitor.recordUpdateRequest();
        Thread.sleep(1L);
        monitor.recordEdtDispatch(first);
        monitor.recordUpdateCompleted(first);

        long second = monitor.recordUpdateRequest();
        Thread.sleep(1L);
        monitor.recordEdtDispatch(second);
        monitor.recordUpdateCompleted(second);

        var result = monitor.snapshot();

        assertEquals(2L, result.requestedUpdates());
        assertEquals(2L, result.completedUpdates());
        assertTrue(result.elapsedMillis() >= 0.0);
        assertTrue(result.meanUpdateLatencyMillis() >= 0.0);
        assertTrue(result.maxUpdateLatencyMillis() >= 0.0);
        assertTrue(result.updateRatePerSecond() >= 0.0);
        assertEquals(2L, result.delayedUpdates());
    }
}
