package pcd.assignment2.common;

import java.util.Locale;

/**
 * Represents an immutable filesystem statistics report.
 * Contains information about directory scanning results including file distribution across size bands.
 *
 * @param directory  The scanned directory path.
 * @param maxFS      The maximum file size boundary, stored in bytes.
 * @param nb         The number of size bands dividing the [0, maxFS] range.
 * @param bandsCount The counts of files falling within each band.
 * @param totalFiles The total count of files scanned recursively.
 * @param durationMs The elapsed time of the scan, in milliseconds.
 */
public record FSReport(
    String directory,
    long maxFS,
    int nb,
    long[] bandsCount,
    long totalFiles,
    long durationMs
) {
    public FSReport {
        bandsCount = bandsCount.clone();
    }

    /**
     * Formats the elapsed time as seconds plus milliseconds.
     *
     * @param durationMs duration in milliseconds
     * @return a human-readable duration such as {@code 1.234 s (1234 ms)}
     */
    public static String formatDuration(long durationMs) {
        return String.format(Locale.US, "%.3f s (%d ms)", durationMs / 1000.0, durationMs);
    }

    /**
     * Formats the elapsed time for this report.
     *
     * @return a human-readable duration string
     */
    public String formatDuration() {
        return formatDuration(durationMs);
    }

    @Override
    public long[] bandsCount() {
        return bandsCount.clone();
    }

    /**
     * Determines which size band index a given file size belongs to.
     *
     * @param size  The size of the file in bytes.
     * @param maxFS The maximum file size threshold.
     * @param nb    The total number of bands dividing [0, maxFS].
     * @return The 0-based band index (from 0 to nb, where nb represents size > maxFS).
     */
    public static int getBandIndex(long size, long maxFS, int nb) {
        if (size > maxFS) {
            return nb;
        }
        if (nb <= 0) {
            return 0;
        }
        double bandWidth = (double) maxFS / nb;
        if (bandWidth <= 0) {
            return 0;
        }
        int idx = (int) (size / bandWidth);
        return Math.clamp(idx, 0, nb - 1);
    }

    /**
     * Gets a human-readable text label describing a specific size band's range.
     *
     * @param index The band index.
     * @return A formatted String representing the size range (e.g. "[0 - 1,000] bytes").
     */
    public String getBandLabel(int index) {
        return getBandLabel(index, SizeUnit.BYTES);
    }

    /**
     * Formats a size band range label given the index, maxFS threshold, total number of bands, and unit.
     *
     * @param index the band index (0 to nb)
     * @param maxFS the maximum file size threshold in bytes
     * @param nb    the total number of size bands
     * @param unit  the display size unit
     * @return formatted String representing the band range
     */
    public static String formatBandLabel(int index, long maxFS, int nb, SizeUnit unit) {
        if (index < 0 || index > nb) {
            throw new IllegalArgumentException("Index out of bounds: " + index);
        }
        if (index == nb) {
            return String.format("> %s", unit.format(maxFS));
        }
        double bandWidth = (double) maxFS / nb;
        long min = Math.round(index * bandWidth);
        long max = Math.round((index + 1) * bandWidth) - 1;
        if (index == nb - 1) {
            max = maxFS;
        }
        return String.format("[%s - %s]", unit.format(min), unit.format(max));
    }

    /**
     * Gets a human-readable text label describing a specific size band's range.
     *
     * @param index The band index.
     * @param unit  the display unit to use for the formatted range
     * @return A formatted String representing the size range.
     */
    public String getBandLabel(int index, SizeUnit unit) {
        return formatBandLabel(index, maxFS, nb, unit);
    }
}
