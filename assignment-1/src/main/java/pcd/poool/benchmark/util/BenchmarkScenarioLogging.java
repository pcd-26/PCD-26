package pcd.poool.benchmark;

import java.util.Locale;

/**
 * Minimal progress logging helpers for benchmark scenarios.
 */
final class BenchmarkScenarioLogging {

    private BenchmarkScenarioLogging() {
    }

    static String scenarioLabel(BenchmarkConfig config) {
        return String.format(
                Locale.US,
                "implementation=%s balls=%d workers=%d steps=%d",
                config.implementation().name().toLowerCase(Locale.ROOT),
                config.balls(),
                config.effectiveThreads(),
                config.steps());
    }

    static void printScenarioStart(BenchmarkConfig config) {
        System.out.printf(Locale.US, "scenario_start %s%n", scenarioLabel(config));
    }

    static void printScenarioDone(BenchmarkConfig config, int measuredRuns) {
        System.out.printf(Locale.US, "scenario_done %s measured_runs=%d%n", scenarioLabel(config), measuredRuns);
    }
}
