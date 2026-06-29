package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BenchmarkInstrumentationTest {

    @Test
    void zeroInstrumentationStartsEmpty() {
        var zero = BenchmarkInstrumentation.zero();

        assertTrue(zero.isEmpty());
        assertEquals(0.0, zero.syncTimeMillis(), 1e-9);
        assertEquals(0L, zero.lockAcquisitions());
        assertEquals(0.0, zero.stateReadTimeMillis(), 1e-9);
        assertEquals(0.0, zero.partitionTimeMillis(), 1e-9);
        assertEquals(0.0, zero.movementTimeMillis(), 1e-9);
    }

    @Test
    void plusCombinesAllMetrics() {
        var first = new BenchmarkInstrumentation(1.0, 2.0, 3.0, 4.0, 5L, 6L, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1);
        var second = new BenchmarkInstrumentation(0.5, 1.5, 2.5, 3.5, 4L, 3L, 1.5, 1.6, 1.7, 1.8, 1.9, 2.0, 2.1);

        var combined = first.plus(second);

        assertFalse(combined.isEmpty());
        assertEquals(1.5, combined.syncTimeMillis(), 1e-9);
        assertEquals(3.5, combined.aggregationTimeMillis(), 1e-9);
        assertEquals(5.5, combined.taskSubmissionTimeMillis(), 1e-9);
        assertEquals(7.5, combined.joinOrFutureWaitMillis(), 1e-9);
        assertEquals(9L, combined.lockAcquisitions());
        assertEquals(9L, combined.submittedTasks());
        assertEquals(2.0, combined.stateReadTimeMillis(), 1e-9);
        assertEquals(2.2, combined.partitionTimeMillis(), 1e-9);
        assertEquals(2.4, combined.movementTimeMillis(), 1e-9);
        assertEquals(2.6, combined.holeInteractionTimeMillis(), 1e-9);
        assertEquals(2.8, combined.collisionDetectionTimeMillis(), 1e-9);
        assertEquals(3.0, combined.collisionResolutionTimeMillis(), 1e-9);
        assertEquals(3.2, combined.mergeApplyTimeMillis(), 1e-9);
    }
}
