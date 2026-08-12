package pcd.fsstat.common;

import java.util.Locale;

/** Immutable result of a filesystem statistics scan. */
public record FSReport(
    String directory,
    long maxFS,
    int nb,
    long[] bandsCount,
    long totalFiles,
    long durationMs
) {
    /** Stores a defensive copy of the bands array inside the immutable report. */
    public FSReport {
        bandsCount = bandsCount.clone();
    }

    /** Formats a duration as seconds and milliseconds. */
    public static String formatDuration(long durationMs) {
        return String.format(Locale.US, "%.3f s (%d ms)", durationMs / 1000.0, durationMs);
    }

    /** Formats this report's duration. */
    public String formatDuration() {
        return formatDuration(durationMs);
    }

    /** Returns a defensive copy of the file-count distribution. */
    @Override
    public long[] bandsCount() {
        return bandsCount.clone();
    }

    /** Finds the band index for a file size. */
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
        if (idx >= nb) {
            idx = nb - 1;
        }
        if (idx < 0) {
            idx = 0;
        }
        return idx;
    }

    /** Formats a band label in bytes. */
    public String getBandLabel(int index) {
        return getBandLabel(index, SizeUnit.BYTES);
    }

    /** Formats a band label using the selected unit. */
    public String getBandLabel(int index, SizeUnit unit) {
        return formatBandLabel(maxFS, nb, index, unit);
    }

    /** Formats a size-band range label. */
    public static String formatBandLabel(long maxFS, int nb, int index, SizeUnit unit) {
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
}
