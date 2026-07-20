package pcd.assignment2.common;

import java.io.File;
import java.io.IOException;
import java.util.Set;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Utility methods for filesystem operations shared across processing paradigms.
 */
public final class FSUtils {

    private FSUtils() { }

    /**
     * Safely lists files in a directory while guarding against symlink cycles
     * and I/O access resolution failures.
     *
     * @param dir          the directory to list files from
     * @param visitedPaths set of canonical paths already visited to prevent cycles
     * @return array of child files, or {@code null} if a cycle is detected, access fails, or dir is not readable
     */
    public static File[] listFilesSafely(File dir, Set<String> visitedPaths) {
        try {
            String canonicalPath = dir.getCanonicalPath();
            if (!visitedPaths.add(canonicalPath)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return dir.listFiles();
    }

    /**
     * Creates an immutable FSReport snapshot from AtomicLong counters.
     *
     * @param directory  the scanned directory
     * @param maxFS      maximum size threshold
     * @param nb         number of bands
     * @param bandsCount array of AtomicLong counters per band
     * @param totalFiles AtomicLong total files counter
     * @param startTime  scan start timestamp in ms
     * @return a new FSReport instance representing the current snapshot
     */
    public static FSReport createReportSnapshot(
        String directory,
        long maxFS,
        int nb,
        AtomicLong[] bandsCount,
        AtomicLong totalFiles,
        long startTime
    ) {
        long[] bands = new long[nb + 1];
        for (int i = 0; i <= nb; i++) {
            bands[i] = bandsCount[i].get();
        }
        return new FSReport(
            directory,
            maxFS,
            nb,
            bands,
            totalFiles.get(),
            System.currentTimeMillis() - startTime
        );
    }

    /**
     * Creates an immutable FSReport snapshot from LongAdder counters.
     *
     * @param directory  the scanned directory
     * @param maxFS      maximum size threshold
     * @param nb         number of bands
     * @param bandsCount array of LongAdder counters per band
     * @param totalFiles LongAdder total files counter
     * @param startTime  scan start timestamp in ms
     * @return a new FSReport instance representing the current snapshot
     */
    public static FSReport createReportSnapshot(
        String directory,
        long maxFS,
        int nb,
        LongAdder[] bandsCount,
        LongAdder totalFiles,
        long startTime
    ) {
        long[] bands = new long[nb + 1];
        for (int i = 0; i <= nb; i++) {
            bands[i] = bandsCount[i].sum();
        }
        return new FSReport(
            directory,
            maxFS,
            nb,
            bands,
            totalFiles.sum(),
            System.currentTimeMillis() - startTime
        );
    }

    /**
     * Formats a size band range label given the index, maxFS threshold, total number of bands, and unit.
     * Delegates to {@link FSReport#formatBandLabel(int, long, int, SizeUnit)}.
     *
     * @param index the band index (0 to nb)
     * @param maxFS the maximum file size threshold in bytes
     * @param nb    the total number of size bands
     * @param unit  the display size unit
     * @return formatted String representing the band range
     */
    public static String formatBandLabel(int index, long maxFS, int nb, SizeUnit unit) {
        return FSReport.formatBandLabel(index, maxFS, nb, unit);
    }
}
