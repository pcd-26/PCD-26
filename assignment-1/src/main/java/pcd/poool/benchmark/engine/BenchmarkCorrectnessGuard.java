package pcd.poool.benchmark.engine;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import pcd.poool.benchmark.config.BenchmarkConfig;
import pcd.poool.benchmark.core.BenchmarkRunner;
import pcd.poool.benchmark.core.BenchmarkStateFingerprint;

/**
 * Compares benchmarked implementations on the same scenario and rejects
 * invalid final states.
 */
public final class BenchmarkCorrectnessGuard {

    private final Map<ScenarioKey, BenchmarkStateFingerprint> baselines = new HashMap<>();

    /**
     * Wraps a workload with correctness validation.
     *
     * @param config benchmark configuration
     * @param workload workload to validate
     * @return wrapped workload that enforces the correctness guard
     */
    public BenchmarkRunner.BenchmarkExecutionWorkload wrap(
            BenchmarkConfig config,
            BenchmarkRunner.BenchmarkExecutionWorkload workload) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(workload, "workload");
        return () -> {
            BenchmarkRunner.BenchmarkExecution execution = workload.run();
            verify(config, execution.fingerprint());
            return execution;
        };
    }

    /**
     * Validates a fingerprint against the scenario baseline.
     *
     * @param config benchmark configuration
     * @param fingerprint final-state fingerprint produced by the run
     */
    public synchronized void verify(BenchmarkConfig config, BenchmarkStateFingerprint fingerprint) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (!fingerprint.isValid()) {
            throw new IllegalStateException(buildFailureMessage(config, "invalid final-state invariants"));
        }

        ScenarioKey key = ScenarioKey.from(config);
        BenchmarkStateFingerprint baseline = baselines.get(key);
        if (config.implementation() == BenchmarkConfig.ImplementationType.SEQUENTIAL) {
            baselines.put(key, fingerprint);
            return;
        }
        if (baseline == null) {
            throw new IllegalStateException(buildFailureMessage(config, "missing sequential baseline"));
        }
        if (!fingerprint.equivalentTo(baseline)) {
            throw new IllegalStateException(buildFailureMessage(config, "final state mismatch against sequential baseline"));
        }
    }

    private String buildFailureMessage(BenchmarkConfig config, String reason) {
        return String.format(Locale.US,
                "correctness check failed implementation=%s balls=%d threads=%d steps=%d seed=%d reason=%s",
                config.implementation().name().toLowerCase(Locale.ROOT),
                config.balls(),
                config.threads(),
                config.steps(),
                config.seed(),
                reason);
    }

    private record ScenarioKey(int balls, int steps, long seed) {
        private static ScenarioKey from(BenchmarkConfig config) {
            return new ScenarioKey(config.balls(), config.steps(), config.seed());
        }
    }
}
