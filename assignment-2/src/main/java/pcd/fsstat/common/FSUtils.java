package pcd.fsstat.common;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Shared helpers used by the three FSStat implementations. */
public final class FSUtils {
    /** Prevents instantiation of this utility class. */
    private FSUtils() {
    }

    /** Creates an array of zero-initialized AtomicLong counters. */
    public static AtomicLong[] initAtomicLongs(int size) {
        AtomicLong[] counters = new AtomicLong[size];
        for (int i = 0; i < size; i++) {
            counters[i] = new AtomicLong(0);
        }
        return counters;
    }

    /** Converts AtomicLong counters into a plain long array snapshot. */
    public static long[] toLongArray(AtomicLong[] counters) {
        long[] values = new long[counters.length];
        for (int i = 0; i < counters.length; i++) {
            values[i] = counters[i].get();
        }
        return values;
    }

    /** Converts LongAdder counters into a plain long array snapshot. */
    public static long[] toLongArray(LongAdder[] counters) {
        long[] values = new long[counters.length];
        for (int i = 0; i < counters.length; i++) {
            values[i] = counters[i].sum();
        }
        return values;
    }

    /** Builds a report from already materialized count values. */
    public static FSReport createReport(
        String directory,
        long maxFS,
        int nb,
        long[] bandsCount,
        long totalFiles,
        long startTime
    ) {
        return new FSReport(directory, maxFS, nb, bandsCount, totalFiles, System.currentTimeMillis() - startTime);
    }

    /** Builds a report by snapshotting AtomicLong counters. */
    public static FSReport createReport(
        String directory,
        long maxFS,
        int nb,
        AtomicLong[] bandsCount,
        AtomicLong totalFiles,
        long startTime
    ) {
        return createReport(directory, maxFS, nb, toLongArray(bandsCount), totalFiles.get(), startTime);
    }

    /** Builds a report by snapshotting LongAdder counters. */
    public static FSReport createReport(
        String directory,
        long maxFS,
        int nb,
        LongAdder[] bandsCount,
        LongAdder totalFiles,
        long startTime
    ) {
        return createReport(directory, maxFS, nb, toLongArray(bandsCount), totalFiles.sum(), startTime);
    }
}
