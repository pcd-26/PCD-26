package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BenchmarkCorrectnessGuardTest {

    @Test
    void matchingSequentialAndThreadedFingerprintsPassForTheSameScenario() {
        var guard = new BenchmarkCorrectnessGuard();
        var sequentialConfig = scenario(BenchmarkConfig.ImplementationType.SEQUENTIAL);
        var threadedConfig = scenario(BenchmarkConfig.ImplementationType.THREADS);
        var fingerprint = new BenchmarkStateFingerprint(42L, 77L, 12, 3, false, false, false, true);

        assertDoesNotThrow(() -> guard.verify(sequentialConfig, fingerprint));
        assertDoesNotThrow(() -> guard.verify(threadedConfig, fingerprint));
    }

    @Test
    void invalidFingerprintsAreRejected() {
        var guard = new BenchmarkCorrectnessGuard();
        var config = scenario(BenchmarkConfig.ImplementationType.SEQUENTIAL);
        var invalidFingerprint = new BenchmarkStateFingerprint(42L, 77L, 12, 3, false, false, true, true);

        var ex = assertThrows(IllegalStateException.class, () -> guard.verify(config, invalidFingerprint));
        assertTrue(ex.getMessage().contains("invalid final-state invariants"));
        assertTrue(ex.getMessage().contains("implementation=sequential"));
    }

    @Test
    void mismatchedFingerprintsAreRejectedAgainstTheSequentialBaseline() {
        var guard = new BenchmarkCorrectnessGuard();
        var sequentialConfig = scenario(BenchmarkConfig.ImplementationType.SEQUENTIAL);
        var threadedConfig = scenario(BenchmarkConfig.ImplementationType.THREADS);
        var baseline = new BenchmarkStateFingerprint(42L, 77L, 12, 3, false, false, false, true);
        var mismatch = new BenchmarkStateFingerprint(99L, 88L, 11, 4, false, false, false, true);

        assertDoesNotThrow(() -> guard.verify(sequentialConfig, baseline));
        var ex = assertThrows(IllegalStateException.class, () -> guard.verify(threadedConfig, mismatch));
        assertTrue(ex.getMessage().contains("final state mismatch against sequential baseline"));
        assertTrue(ex.getMessage().contains("implementation=threads"));
        assertTrue(ex.getMessage().contains("seed=12345"));
    }

    private static BenchmarkConfig scenario(BenchmarkConfig.ImplementationType implementation) {
        return BenchmarkConfig.defaults()
                .withImplementation(implementation)
                .withBalls(12)
                .withThreads(4)
                .withSteps(8)
                .withSeed(12345L)
                .withWarmupRuns(0)
                .withMeasuredRuns(1)
                .withOutputDir(Path.of("target"));
    }
}
