package pcd.fsstat.paradigm.reactive;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.schedulers.Schedulers;
import pcd.fsstat.common.FSReport;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Computes directory file statistics reactively using RxJava 3. */
public class ReactiveFSStat {

    /** Immutable accumulator used by the Rx scan pipeline. */
    private static class ReactiveScanState {
        final String directoryPath;
        final long maximumFileSizeBytes;
        final int numberOfBands;
        final long totalFileCount;
        final long[] fileCountsPerBand;
        final long scanStartTime;

        /** Creates the initial empty accumulator for a scan. */
        ReactiveScanState(String directoryPath, long maximumFileSizeBytes, int numberOfBands, long scanStartTime) {
            this.directoryPath = directoryPath;
            this.maximumFileSizeBytes = maximumFileSizeBytes;
            this.numberOfBands = numberOfBands;
            this.totalFileCount = 0;
            this.fileCountsPerBand = new long[numberOfBands + 1];
            this.scanStartTime = scanStartTime;
        }

        /** Creates the next accumulator after receiving one scanned file. */
        ReactiveScanState(ReactiveScanState previousState, File currentFile) {
            this.directoryPath = previousState.directoryPath;
            this.maximumFileSizeBytes = previousState.maximumFileSizeBytes;
            this.numberOfBands = previousState.numberOfBands;
            this.totalFileCount = previousState.totalFileCount + 1;
            this.fileCountsPerBand = previousState.fileCountsPerBand.clone();
            int bandIndex = FSReport.getBandIndex(currentFile.length(), maximumFileSizeBytes, numberOfBands);
            this.fileCountsPerBand[bandIndex]++;
            this.scanStartTime = previousState.scanStartTime;
        }

        /** Converts the accumulator snapshot into the public report model. */
        FSReport toReport() {
            return new FSReport(
                directoryPath,
                maximumFileSizeBytes,
                numberOfBands,
                fileCountsPerBand,
                totalFileCount,
                System.currentTimeMillis() - scanStartTime
            );
        }
    }

    /** Builds a reactive report stream for the requested directory scan. */
    public static Observable<FSReport> getFSReport(String directory, long maximumFileSizeBytes, int numberOfBands) {
        return Observable.defer(() -> { // defer: build the real pipeline only when a subscriber starts the scan.
            // Validate lazily so errors are delivered through the Observable contract.
            File rootDirectory = new File(directory);
            if (!rootDirectory.exists() || !rootDirectory.isDirectory()) {
                return Observable.error(new IllegalArgumentException("Target is not a valid directory: " + directory)); // error: terminate through onError instead of throwing immediately.
            }
            long scanStartTime = System.currentTimeMillis();
            ReactiveScanState initialState = new ReactiveScanState(
                directory,
                maximumFileSizeBytes,
                numberOfBands,
                scanStartTime
            );

            return scanFiles(rootDirectory)
                .subscribeOn(Schedulers.io()) // subscribeOn: run the blocking directory walk on RxJava's I/O scheduler.
                .scan(initialState, (previousState, currentFile) -> { // scan: receive old state + emitted File, then return the next state.
                    return new ReactiveScanState(previousState, currentFile);
                })
                .skip(1) // skip: remove the initial empty accumulator emitted before the first File.
                .defaultIfEmpty(initialState) // defaultIfEmpty: still emit an empty state when the directory has no files.
                .map(state -> state.toReport()) // map: convert each internal accumulator snapshot into a public FSReport.
                .sample(100, TimeUnit.MILLISECONDS, true); // sample: publish at most one report every 100 ms, including the final one.
        });
    }

    /** Creates an Observable that emits every regular file under the root directory. */
    private static Observable<File> scanFiles(File rootDirectory) {
        return Observable.create(emitter -> { // create: adapt the recursive filesystem walk into an Observable<File> source.
            try {
                Set<String> visitedDirectories = new HashSet<>();
                emitDirectoryContents(rootDirectory, emitter, visitedDirectories);
                if (!emitter.isDisposed()) {
                    emitter.onComplete(); // onComplete: signal that no more files will be emitted.
                }
            } catch (Throwable t) {
                if (!emitter.isDisposed()) {
                    emitter.onError(t); // onError: propagate traversal failures through the reactive stream.
                }
            }
        });
    }

    /** Walks directories recursively and emits regular files while respecting cancellation. */
    private static void emitDirectoryContents(File directory, ObservableEmitter<File> emitter, Set<String> visitedDirectories) {
        if (emitter.isDisposed()) {
            return;
        }

        // Canonical paths keep symbolic-link cycles from being traversed again.
        try {
            String canonicalPath = directory.getCanonicalPath();
            if (!visitedDirectories.add(canonicalPath)) {
                return;
            }
        } catch (IOException ignored) {
            return;
        }

        File[] directoryChildren = directory.listFiles();
        if (directoryChildren == null) {
            return;
        }
        // Recurse into directories and emit only regular files.
        for (File childFile : directoryChildren) {
            if (emitter.isDisposed()) {
                return;
            }
            if (childFile.isDirectory()) {
                emitDirectoryContents(childFile, emitter, visitedDirectories);
            } else if (childFile.isFile()) {
                emitter.onNext(childFile); // onNext: push one discovered regular file to the downstream Rx pipeline.
            }
        }
    }
}
