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
     * Validates if a target string path points to an existing directory on the filesystem.
     *
     * @param path Directory path string to validate.
     * @return Validated {@link File} object.
     * @throws IllegalArgumentException if path is null, empty, does not exist, or is not a directory.
     */
    public static File validateDirectory(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Target directory path cannot be null or empty.");
        }
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Target path is not a valid directory: " + path);
        }
        return dir;
    }

    /**
     * Initializes an array of {@link LongAdder} objects of the specified size.
     *
     * @param size The number of elements in the array.
     * @return An array populated with non-null LongAdder instances.
     */
    public static LongAdder[] initLongAdders(int size) {
        LongAdder[] adders = new LongAdder[size];
        for (int i = 0; i < size; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }

    /**
     * Initializes an array of {@link AtomicLong} objects of the specified size.
     *
     * @param size The number of elements in the array.
     * @return An array populated with non-null AtomicLong instances initialized to 0.
     */
    public static AtomicLong[] initAtomicLongs(int size) {
        AtomicLong[] atomics = new AtomicLong[size];
        for (int i = 0; i < size; i++) {
            atomics[i] = new AtomicLong(0);
        }
        return atomics;
    }

    /**
     * Creates an immutable FSReport snapshot given extracted band array and total file count.
     *
     * @param directory  the scanned directory
     * @param maxFS      maximum size threshold
     * @param nb         number of bands
     * @param bandsCount array of file counts per band
     * @param totalFiles total files count
     * @param startTime  scan start timestamp in ms
     * @return a new FSReport instance representing the current snapshot
     */
    public static FSReport createReportSnapshot(
        String directory,
        long maxFS,
        int nb,
        long[] bandsCount,
        long totalFiles,
        long startTime
    ) {
        return new FSReport(
            directory,
            maxFS,
            nb,
            bandsCount,
            totalFiles,
            System.currentTimeMillis() - startTime
        );
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
        return createReportSnapshot(directory, maxFS, nb, bands, totalFiles.get(), startTime);
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
        return createReportSnapshot(directory, maxFS, nb, bands, totalFiles.sum(), startTime);
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
