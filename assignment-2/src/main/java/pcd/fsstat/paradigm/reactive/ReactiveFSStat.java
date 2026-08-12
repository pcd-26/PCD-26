package pcd.fsstat.paradigm.reactive;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.schedulers.Schedulers;
import pcd.fsstat.common.FSReport;

import java.io.File;
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
    public static Observable<FSReport> getFSReport(String directory, long maxFS, int nb) {
        return Observable.defer(() -> {
            // Validate lazily so errors are delivered through the Observable contract.
            File rootDirectory = new File(directory);
            if (!rootDirectory.exists() || !rootDirectory.isDirectory()) {
                return Observable.error(new IllegalArgumentException("Target is not a valid directory: " + directory));
            }
            long scanStartTime = System.currentTimeMillis();
            ReactiveScanState initialState = new ReactiveScanState(directory, maxFS, nb, scanStartTime);

            // Convert emitted files into immutable report snapshots and throttle progress updates.
            return scanFiles(rootDirectory)
                .subscribeOn(Schedulers.io())
                .scan(initialState, ReactiveScanState::new)
                .skip(1)
                .defaultIfEmpty(initialState)
                .map(ReactiveScanState::toReport)
                .sample(100, TimeUnit.MILLISECONDS, true);
        });
    }

    /** Creates an Observable that emits every regular file under the root directory. */
    private static Observable<File> scanFiles(File rootDirectory) {
        return Observable.create(emitter -> {
            try {
                java.util.Set<String> visitedDirectories = new java.util.HashSet<>();
                emitDirectoryContents(rootDirectory, emitter, visitedDirectories);
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

    /** Walks directories recursively and emits regular files while respecting cancellation. */
    private static void emitDirectoryContents(File directory, ObservableEmitter<File> emitter, java.util.Set<String> visitedDirectories) {
        if (emitter.isDisposed()) {
            return;
        }

        // Canonical paths keep symbolic-link cycles from being traversed again.
        try {
            String canonicalPath = directory.getCanonicalPath();
            if (!visitedDirectories.add(canonicalPath)) {
                return;
            }
        } catch (java.io.IOException ignored) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        // Recurse into directories and emit only regular files.
        for (File file : files) {
            if (emitter.isDisposed()) {
                return;
            }
            if (file.isDirectory()) {
                emitDirectoryContents(file, emitter, visitedDirectories);
            } else if (file.isFile()) {
                emitter.onNext(file);
            }
        }
    }
}
