package pcd.assignment2.reactive;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.schedulers.Schedulers;
import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSUtils;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.HashSet;

/**
 * Computes directory file statistics reactively using RxJava 3.
 *
 * <p><strong>Shared State & Synchronization Strategy:</strong>
 * <ul>
 *   <li>This implementation adopts functional programming principles, completely avoiding shared mutable state.</li>
 *   <li>The statistics are collected inside an immutable {@link Accumulator} record/class, which accumulates the counts
 *       as files flow through the stream using the {@link Observable#scan} operator.</li>
 *   <li>The directory traversal runs concurrently on {@link Schedulers#io()}.</li>
 *   <li>Cancellation is handled natively via subscription disposal (the subscription's {@code dispose()} method).
 *       The directory walking loop monitors {@link ObservableEmitter#isDisposed()} and halts immediately if canceled.</li>
 *   <li>To avoid saturating the UI consumer (e.g. Swing thread), updates are throttled using {@code sample()},
 *       while guaranteeing that the final accumulated report is always delivered upon completion.</li>
 * </ul>
 */
public class ReactiveFSStat {

    /**
     * Immutable helper class that aggregates file statistics (total files and size band distribution)
     * as files are emitted through the reactive stream.
     */
    private static class Accumulator {
        /** The root directory path of the scan. */
        final String directory;
        /** The maximum file size threshold. */
        final long maxFS;
        /** The number of file size bands. */
        final int nb;
        /** The current total of files scanned. */
        final long totalFiles;
        /** The current count of files in each size band. */
        final long[] bandsCount;
        /** The timestamp when the scan started. */
        final long startTime;

        /**
         * Creates the initial accumulator state.
         *
         * @param directory The scanned root directory.
         * @param maxFS     The maximum file size limit.
         * @param nb        The number of size bands.
         * @param startTime The start time timestamp.
         */
        Accumulator(String directory, long maxFS, int nb, long startTime) {
            this.directory = directory;
            this.maxFS = maxFS;
            this.nb = nb;
            this.totalFiles = 0;
            this.bandsCount = new long[nb + 1];
            this.startTime = startTime;
        }

        /**
         * Accumulates a scanned file into the statistics.
         *
         * @param prev The previous accumulator state.
         * @param file The file to aggregate.
         */
        Accumulator(Accumulator prev, File file) {
            this.directory = prev.directory;
            this.maxFS = prev.maxFS;
            this.nb = prev.nb;
            this.totalFiles = prev.totalFiles + 1;
            this.bandsCount = prev.bandsCount.clone();
            int idx = FSReport.getBandIndex(file.length(), maxFS, nb);
            this.bandsCount[idx]++;
            this.startTime = prev.startTime;
        }

        /**
         * Converts the current accumulation state to a user-facing FSReport snapshot.
         *
         * @return A new FSReport instance representing the current state.
         */
        FSReport toReport() {
            return new FSReport(
                directory,
                maxFS,
                nb,
                bandsCount,
                totalFiles,
                System.currentTimeMillis() - startTime
            );
        }
    }

    /**
     * Returns a reactive stream (Observable) that scans a directory and emits statistical reports.
     *
     * @param directory The root directory path to scan.
     * @param maxFS     The maximum file size threshold.
     * @param nb        The number of file size bands.
     * @return An Observable emitting FSReport instances periodically, and completing when the scan is finished.
     */
    public static Observable<FSReport> getFSReport(String directory, long maxFS, int nb) {
        return Observable.defer(() -> {
            File rootDir = new File(directory);
            if (!rootDir.exists() || !rootDir.isDirectory()) {
                return Observable.error(new IllegalArgumentException("Target is not a valid directory: " + directory));
            }
            long startTime = System.currentTimeMillis();
            Accumulator initial = new Accumulator(directory, maxFS, nb, startTime);

            return walk(rootDir)
                .subscribeOn(Schedulers.io())
                .scan(initial, Accumulator::new)
                .skip(1) // Skip the initial accumulator state
                .defaultIfEmpty(initial)
                .map(Accumulator::toReport)
                .sample(100, TimeUnit.MILLISECONDS, true); // Sample updates, but ensure the final one is emitted
        });
    }

    /**
     * Creates an Observable stream emitting regular files found inside a directory tree.
     *
     * @param rootDir The root directory to scan.
     * @return An Observable emitting File instances.
     */
    private static Observable<File> walk(File rootDir) {
        return Observable.create(emitter -> {
            try {
                Set<String> visitedPaths = new HashSet<>();
                walkRecursive(rootDir, emitter, visitedPaths);
                if (!emitter.isDisposed()) {
                    emitter.onComplete();
                }
            } catch (Throwable t) {
                if (!emitter.isDisposed()) {
                    emitter.onError(t);
                }
            }
        });
    }

    /**
     * Recursively traverses directories, emitting regular files found to the observer emitter.
     * Halts traversal immediately if the stream is disposed.
     *
     * @param dir          The current directory.
     * @param emitter      The Observable emitter to push files to.
     * @param visitedPaths Set tracking already visited canonical directory paths to prevent symlink loops.
     */
    private static void walkRecursive(File dir, ObservableEmitter<File> emitter, Set<String> visitedPaths) {
        if (emitter.isDisposed()) {
            return;
        }

        File[] files = FSUtils.listFilesSafely(dir, visitedPaths);
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (emitter.isDisposed()) {
                return;
            }
            if (file.isDirectory()) {
                walkRecursive(file, emitter, visitedPaths);
            } else if (file.isFile()) {
                emitter.onNext(file);
            }
        }
    }
}
