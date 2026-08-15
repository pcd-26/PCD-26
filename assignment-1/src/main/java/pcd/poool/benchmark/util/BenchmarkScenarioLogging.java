package pcd.poool.benchmark.util;

import java.util.Locale;
import pcd.poool.benchmark.config.BenchmarkConfig;

/**
 * Minimal progress logging helpers for benchmark scenarios.
 */
public final class BenchmarkScenarioLogging {

    private BenchmarkScenarioLogging() {
    }

    public static String scenarioLabel(BenchmarkConfig config) {
        return String.format(
                Locale.US,
                "implementation=%s balls=%d workers=%d steps=%d",
                config.implementation().name().toLowerCase(Locale.ROOT),
                config.balls(),
                config.effectiveThreads(),
                config.steps());
    }

    public static void printScenarioStart(BenchmarkConfig config) {
        System.out.printf(Locale.US, "scenario_start %s%n", scenarioLabel(config));
    }

    public static void printScenarioDone(BenchmarkConfig config, int measuredRuns) {
        System.out.printf(Locale.US, "scenario_done %s measured_runs=%d%n", scenarioLabel(config), measuredRuns);
    }
}
