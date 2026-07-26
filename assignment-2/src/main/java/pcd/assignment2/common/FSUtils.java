package pcd.assignment2.common;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Shared helpers used by the three FSStat implementations. */
public final class FSUtils {
    private FSUtils() {
    }

    public static AtomicLong[] initAtomicLongs(int size) {
        AtomicLong[] counters = new AtomicLong[size];
        for (int i = 0; i < size; i++) {
            counters[i] = new AtomicLong(0);
        }
        return counters;
    }

    public static long[] toLongArray(AtomicLong[] counters) {
        long[] values = new long[counters.length];
        for (int i = 0; i < counters.length; i++) {
            values[i] = counters[i].get();
        }
        return values;
    }

    public static long[] toLongArray(LongAdder[] counters) {
        long[] values = new long[counters.length];
        for (int i = 0; i < counters.length; i++) {
            values[i] = counters[i].sum();
        }
        return values;
    }

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
