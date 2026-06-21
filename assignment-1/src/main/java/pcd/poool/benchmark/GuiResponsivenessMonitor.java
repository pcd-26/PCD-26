package pcd.poool.benchmark;

/**
 * Collects GUI responsiveness metrics during a scripted rendering session.
 *
 * <p>The monitor is intentionally lightweight and thread-safe. The benchmark
 * loop records when updates are requested, when the EDT processes an
 * {@code invokeLater} probe, and when the render call completes.
 */
public final class GuiResponsivenessMonitor {

    private final long delayedUpdateThresholdNanos;
    private long firstUpdateRequestNanos = -1L;
    private long lastUpdateRequestNanos = -1L;
    private long lastUpdateCompletedNanos = -1L;
    private long updateCount;
    private long completedUpdates;
    private long totalUpdateIntervalNanos;
    private long totalUpdateLatencyNanos;
    private long maxUpdateLatencyNanos;
    private long totalEdtDelayNanos;
    private long maxEdtDelayNanos;
    private long delayedUpdates;

    /**
     * Creates a new monitor.
     *
     * @param delayedUpdateThresholdNanos threshold used to count delayed updates
     */
    public GuiResponsivenessMonitor(long delayedUpdateThresholdNanos) {
        if (delayedUpdateThresholdNanos < 0) {
            throw new IllegalArgumentException("delayedUpdateThresholdNanos must be >= 0");
        }
        this.delayedUpdateThresholdNanos = delayedUpdateThresholdNanos;
    }

    /**
     * Records the start of one GUI update cycle.
     *
     * @return the timestamp captured for the update request
     */
    public synchronized long recordUpdateRequest() {
        long now = System.nanoTime();
        if (firstUpdateRequestNanos < 0) {
            firstUpdateRequestNanos = now;
        }
        if (lastUpdateRequestNanos >= 0) {
            totalUpdateIntervalNanos += now - lastUpdateRequestNanos;
        }
        lastUpdateRequestNanos = now;
        updateCount++;
        return now;
    }

    /**
     * Records how long the EDT took to process a queued probe.
     *
     * @param requestNanos timestamp captured when the probe was scheduled
     */
    public synchronized void recordEdtDispatch(long requestNanos) {
        long delay = System.nanoTime() - requestNanos;
        totalEdtDelayNanos += delay;
        maxEdtDelayNanos = Math.max(maxEdtDelayNanos, delay);
    }

    /**
     * Records the completion of a GUI update cycle.
     *
     * @param requestNanos timestamp captured when the update was requested
     */
    public synchronized void recordUpdateCompleted(long requestNanos) {
        long now = System.nanoTime();
        long latency = now - requestNanos;
        totalUpdateLatencyNanos += latency;
        maxUpdateLatencyNanos = Math.max(maxUpdateLatencyNanos, latency);
        if (latency >= delayedUpdateThresholdNanos) {
            delayedUpdates++;
        }
        completedUpdates++;
        lastUpdateCompletedNanos = now;
    }

    /**
     * Creates an immutable snapshot of the collected metrics.
     *
     * @return current GUI responsiveness measurements
     */
    public synchronized GuiResponsivenessResult snapshot() {
        long elapsedNanos = firstUpdateRequestNanos < 0 || lastUpdateCompletedNanos < 0
                ? 0L
                : Math.max(0L, lastUpdateCompletedNanos - firstUpdateRequestNanos);
        double meanUpdateIntervalMillis = completedUpdates <= 1
                ? Double.NaN
                : (totalUpdateIntervalNanos / (double) (completedUpdates - 1)) / BenchmarkRunner.NANOS_PER_MILLISECOND;
        double meanUpdateLatencyMillis = completedUpdates == 0
                ? Double.NaN
                : (totalUpdateLatencyNanos / (double) completedUpdates) / BenchmarkRunner.NANOS_PER_MILLISECOND;
        double maxUpdateLatencyMillis = maxUpdateLatencyNanos / BenchmarkRunner.NANOS_PER_MILLISECOND;
        double meanEdtDelayMillis = completedUpdates == 0
                ? Double.NaN
                : (totalEdtDelayNanos / (double) completedUpdates) / BenchmarkRunner.NANOS_PER_MILLISECOND;
        double maxEdtDelayMillis = maxEdtDelayNanos / BenchmarkRunner.NANOS_PER_MILLISECOND;
        double updateRatePerSecond = elapsedNanos <= 0
                ? 0.0
                : completedUpdates * BenchmarkRunner.NANOS_PER_SECOND / elapsedNanos;
        return new GuiResponsivenessResult(
                updateCount,
                completedUpdates,
                elapsedNanos / BenchmarkRunner.NANOS_PER_MILLISECOND,
                meanUpdateIntervalMillis,
                meanUpdateLatencyMillis,
                maxUpdateLatencyMillis,
                updateRatePerSecond,
                meanEdtDelayMillis,
                maxEdtDelayMillis,
                delayedUpdates);
    }
}
