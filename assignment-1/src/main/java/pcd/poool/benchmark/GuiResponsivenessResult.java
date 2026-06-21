package pcd.poool.benchmark;

import java.util.Locale;

/**
 * Immutable GUI responsiveness measurements captured by the benchmark.
 *
 * @param requestedUpdates number of requested GUI updates
 * @param completedUpdates number of completed GUI updates
 * @param elapsedMillis elapsed benchmark time in milliseconds
 * @param meanUpdateIntervalMillis average time between update requests
 * @param meanUpdateLatencyMillis average time from request to completion
 * @param maxUpdateLatencyMillis maximum request-to-completion latency
 * @param updateRatePerSecond completed updates per second
 * @param meanEdtDelayMillis average invokeLater delay on the EDT
 * @param maxEdtDelayMillis maximum invokeLater delay on the EDT
 * @param delayedUpdates updates that exceeded the configured delay threshold
 */
public record GuiResponsivenessResult(
        long requestedUpdates,
        long completedUpdates,
        double elapsedMillis,
        double meanUpdateIntervalMillis,
        double meanUpdateLatencyMillis,
        double maxUpdateLatencyMillis,
        double updateRatePerSecond,
        double meanEdtDelayMillis,
        double maxEdtDelayMillis,
        long delayedUpdates) {

    @Override
    public String toString() {
        return String.format(Locale.US,
                "GuiResponsivenessResult{requestedUpdates=%d, completedUpdates=%d, elapsedMillis=%.6f, meanUpdateIntervalMillis=%.6f, meanUpdateLatencyMillis=%.6f, maxUpdateLatencyMillis=%.6f, updateRatePerSecond=%.3f, meanEdtDelayMillis=%.6f, maxEdtDelayMillis=%.6f, delayedUpdates=%d}",
                requestedUpdates,
                completedUpdates,
                elapsedMillis,
                meanUpdateIntervalMillis,
                meanUpdateLatencyMillis,
                maxUpdateLatencyMillis,
                updateRatePerSecond,
                meanEdtDelayMillis,
                maxEdtDelayMillis,
                delayedUpdates);
    }
}
