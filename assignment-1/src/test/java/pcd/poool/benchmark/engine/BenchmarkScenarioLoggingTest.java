package pcd.poool.benchmark.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pcd.poool.benchmark.config.BenchmarkConfig;
import pcd.poool.benchmark.util.BenchmarkScenarioLogging;

class BenchmarkScenarioLoggingTest {

    @Test
    void headlessScenarioLabelUsesMinimalStableFormat() {
        var config = BenchmarkConfig.defaults()
                .withImplementation(BenchmarkConfig.ImplementationType.THREADS)
                .withBalls(500)
                .withThreads(4)
                .withSteps(1_000);

        assertEquals(
                "implementation=threads balls=500 workers=4 steps=1000",
                BenchmarkScenarioLogging.scenarioLabel(config));
    }

    @Test
    void scalabilityScenarioLabelUsesMinimalStableFormat() {
        var config = BenchmarkConfig.defaults()
                .withImplementation(BenchmarkConfig.ImplementationType.EXECUTOR)
                .withBalls(2_500)
                .withThreads(8)
                .withSteps(1_000);

        assertEquals(
                "implementation=executor balls=2500 workers=8 steps=1000",
                BenchmarkScenarioLogging.scenarioLabel(config));
    }

    @Test
    void guiScenarioLabelUsesMinimalStableFormat() {
        var config = BenchmarkConfig.defaults()
                .withImplementation(BenchmarkConfig.ImplementationType.SEQUENTIAL)
                .withBalls(100)
                .withThreads(1)
                .withSteps(240);

        assertEquals(
                "implementation=sequential balls=100 workers=1 steps=240",
                BenchmarkScenarioLogging.scenarioLabel(config));
    }
}
