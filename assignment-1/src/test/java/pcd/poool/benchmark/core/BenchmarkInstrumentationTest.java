package pcd.poool.benchmark.core;

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
        assertEquals(0.0, zero.aggregationTimeMillis(), 1e-9);
        assertEquals(0.0, zero.taskSubmissionTimeMillis(), 1e-9);
        assertEquals(0.0, zero.joinOrFutureWaitMillis(), 1e-9);
        assertEquals(0L, zero.lockAcquisitions());
        assertEquals(0L, zero.submittedTasks());
    }

    @Test
    void plusCombinesAllMetrics() {
        var first = new BenchmarkInstrumentation(1.0, 2.0, 3.0, 4.0, 5L, 6L);
        var second = new BenchmarkInstrumentation(0.5, 1.5, 2.5, 3.5, 4L, 3L);

        var combined = first.plus(second);

        assertFalse(combined.isEmpty());
        assertEquals(1.5, combined.syncTimeMillis(), 1e-9);
        assertEquals(3.5, combined.aggregationTimeMillis(), 1e-9);
        assertEquals(5.5, combined.taskSubmissionTimeMillis(), 1e-9);
        assertEquals(7.5, combined.joinOrFutureWaitMillis(), 1e-9);
        assertEquals(9L, combined.lockAcquisitions());
        assertEquals(9L, combined.submittedTasks());
    }
}
