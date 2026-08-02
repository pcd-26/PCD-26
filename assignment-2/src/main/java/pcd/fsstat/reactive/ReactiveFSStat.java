package pcd.fsstat.reactive;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import io.reactivex.rxjava3.schedulers.Schedulers;
import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSUtils;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** Computes directory file statistics reactively using RxJava 3. */
public class ReactiveFSStat {

    private static class ScanState {
        final String directory;
        final long maxFS;
        final int nb;
        final long totalFiles;
        final long[] bandsCount;
        final long startTime;

        ScanState(String directory, long maxFS, int nb, long startTime) {
            this.directory = directory;
            this.maxFS = maxFS;
            this.nb = nb;
            this.totalFiles = 0;
            this.bandsCount = new long[nb + 1];
            this.startTime = startTime;
        }

        ScanState(ScanState previous, File file) {
            this.directory = previous.directory;
            this.maxFS = previous.maxFS;
            this.nb = previous.nb;
            this.totalFiles = previous.totalFiles + 1;
            this.bandsCount = previous.bandsCount.clone();
            int idx = FSReport.getBandIndex(file.length(), maxFS, nb);
            this.bandsCount[idx]++;
            this.startTime = previous.startTime;
        }

        FSReport toReport() {
            return FSUtils.createReport(directory, maxFS, nb, bandsCount, totalFiles, startTime);
        }
    }

    public static Observable<FSReport> getFSReport(String directory, long maxFS, int nb) {
        return Observable.defer(() -> {
            File rootDir = new File(directory);
            if (!rootDir.exists() || !rootDir.isDirectory()) {
                return Observable.error(new IllegalArgumentException("Target is not a valid directory: " + directory));
            }
            long startTime = System.currentTimeMillis();
            ScanState initial = new ScanState(directory, maxFS, nb, startTime);

            return scanFiles(rootDir)
                .subscribeOn(Schedulers.io())
                .scan(initial, ScanState::new)
                .skip(1)
                .defaultIfEmpty(initial)
                .map(ScanState::toReport)
                .sample(100, TimeUnit.MILLISECONDS, true);
        });
    }

    private static Observable<File> scanFiles(File rootDirectory) {
        return Observable.create(emitter -> {
            try {
                java.util.Set<String> seenDirectories = new java.util.HashSet<>();
                visitDirectory(rootDirectory, emitter, seenDirectories);
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

    private static void visitDirectory(File directory, ObservableEmitter<File> emitter, java.util.Set<String> seenDirectories) {
        if (emitter.isDisposed()) {
            return;
        }

        try {
            String canonicalPath = directory.getCanonicalPath();
            if (!seenDirectories.add(canonicalPath)) {
                return;
            }
        } catch (java.io.IOException ignored) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (emitter.isDisposed()) {
                return;
            }
            if (file.isDirectory()) {
                visitDirectory(file, emitter, seenDirectories);
            } else if (file.isFile()) {
                emitter.onNext(file);
            }
        }
    }
}
