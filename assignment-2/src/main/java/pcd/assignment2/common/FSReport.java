package pcd.assignment2.common;

/**
 * Represents an immutable filesystem statistics report.
 * Contains information about directory scanning results including file distribution across size bands.
 *
 * @param directory  The scanned directory path.
 * @param maxFS      The maximum file size boundary.
 * @param nb         The number of size bands dividing the [0, maxFS] range.
 * @param bandsCount The counts of files falling within each band.
 * @param totalFiles The total count of files scanned recursively.
 * @param durationMs The elapsed time of the scan.
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
        if (idx >= nb) {
            idx = nb - 1;
        }
        if (idx < 0) {
            idx = 0;
        }
        return idx;
    }

    /**
     * Gets a human-readable text label describing a specific size band's range.
     *
     * @param index The band index.
     * @return A formatted String representing the size range (e.g. "[0 - 1,000] bytes").
     */
    public String getBandLabel(int index) {
        if (index < 0 || index > nb) {
            throw new IllegalArgumentException("Index out of bounds: " + index);
        }
        if (index == nb) {
            return String.format("> %,d bytes", maxFS);
        }
        double bandWidth = (double) maxFS / nb;
        long min = Math.round(index * bandWidth);
        long max = Math.round((index + 1) * bandWidth) - 1;
        if (index == nb - 1) {
            max = maxFS;
        }
        return String.format("[%,d - %,d] bytes", min, max);
    }
}
