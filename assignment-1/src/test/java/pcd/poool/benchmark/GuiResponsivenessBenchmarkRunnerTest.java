package pcd.poool.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuiResponsivenessBenchmarkRunnerTest {

    @Test
    void defaultsUseTheRequestedMatrix() {
        var request = GuiResponsivenessBenchmarkRunner.defaults();

        assertEquals(List.of(
                BenchmarkConfig.ImplementationType.SEQUENTIAL,
                BenchmarkConfig.ImplementationType.THREADS,
                BenchmarkConfig.ImplementationType.EXECUTOR), request.implementations());
        assertEquals(List.of(100, 500, 1_000, 2_500, 5_000, 10_000), request.balls());
        assertEquals(240, request.steps());
        assertEquals(42L, request.seed());
        assertTrue(request.warmupRuns() >= 2);
        assertTrue(request.measuredRuns() >= 5);
        assertEquals(Path.of("benchmark", "results", "raw-gui-results.csv"), request.outputFile());
    }
}
