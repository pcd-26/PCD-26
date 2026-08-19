package pcd.fsstat.common;

import java.util.Locale;

/** Immutable result of a filesystem statistics scan. */
public record FSReport(
    String directory,
    long maximumFileSizeBytes,
    int numberOfBands,
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

    /** Legacy accessor kept for compatibility with the assignment wording. */
    public long maxFS() {
        return maximumFileSizeBytes;
    }

    /** Legacy accessor kept for compatibility with the assignment wording. */
    public int nb() {
        return numberOfBands;
    }

    /** Finds the band index for a file size. */
    public static int getBandIndex(long fileSizeBytes, long maximumFileSizeBytes, int numberOfBands) {
        if (fileSizeBytes > maximumFileSizeBytes) {
            return numberOfBands;
        }
        if (numberOfBands <= 0) {
            return 0;
        }
        return computeRegularBandIndex(fileSizeBytes, maximumFileSizeBytes, numberOfBands);
    }

    /** Formats a band label in bytes. */
    public String getBandLabel(int index) {
        return getBandLabel(index, SizeUnit.BYTES);
    }

    /** Formats a band label using the selected unit. */
    public String getBandLabel(int index, SizeUnit unit) {
        return formatBandLabel(maximumFileSizeBytes, numberOfBands, index, unit);
    }

    /** Formats a size-band range label. */
    public static String formatBandLabel(long maximumFileSizeBytes, int numberOfBands, int index, SizeUnit unit) {
        if (index < 0 || index > numberOfBands) {
            throw new IllegalArgumentException("Index out of bounds: " + index);
        }
        if (index == numberOfBands) {
            return String.format("> %s", unit.format(maximumFileSizeBytes));
        }
        long minimumBandSizeBytes = getRegularBandLowerBound(maximumFileSizeBytes, numberOfBands, index);
        long maximumBandSizeBytes = getRegularBandUpperBound(maximumFileSizeBytes, numberOfBands, index);
        return String.format("[%s - %s]", unit.format(minimumBandSizeBytes), unit.format(maximumBandSizeBytes));
    }

    /** Computes the regular-band index using the same discrete partition used by the labels. */
    private static int computeRegularBandIndex(long fileSizeBytes, long maximumFileSizeBytes, int numberOfBands) {
        for (int bandIndex = 0; bandIndex < numberOfBands; bandIndex++) {
            if (fileSizeBytes <= getRegularBandUpperBound(maximumFileSizeBytes, numberOfBands, bandIndex)) {
                return bandIndex;
            }
        }
        return numberOfBands - 1;
    }

    /** Returns the inclusive lower bound of a regular band. */
    private static long getRegularBandLowerBound(long maximumFileSizeBytes, int numberOfBands, int index) {
        long domainCardinality = maximumFileSizeBytes + 1;
        return (index * domainCardinality) / numberOfBands;
    }

    /** Returns the inclusive upper bound of a regular band. */
    private static long getRegularBandUpperBound(long maximumFileSizeBytes, int numberOfBands, int index) {
        long domainCardinality = maximumFileSizeBytes + 1;
        return (((long) index + 1) * domainCardinality) / numberOfBands - 1;
    }
}
