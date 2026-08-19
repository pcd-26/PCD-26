package pcd.fsstat.paradigm.eventloop;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileProps;
import io.vertx.core.file.FileSystem;
import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSReportJob;
import pcd.fsstat.common.FSReportListener;

import java.io.File;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Computes directory file statistics asynchronously using Vert.x. */
public class EventLoopFSStat {

    private enum PathValidationStatus {
        VALID,
        DUPLICATE,
        NOT_DIRECTORY
    }

    /** Wraps the outcome of asynchronous directory validation. */
    private record PathValidationResult(PathValidationStatus status) { }

    /** Holds mutable scan state shared across Vert.x callbacks. */
    private static class EventLoopScanState {
        private volatile boolean cancelled = false;
        final AtomicInteger pendingTaskCount = new AtomicInteger(0);
        final AtomicBoolean closed = new AtomicBoolean(false);
        final AtomicBoolean terminalEventEmitted = new AtomicBoolean(false);
        final AtomicLong totalFileCount = new AtomicLong(0);
        final AtomicLong[] fileCountsPerBand;
        final Set<String> visitedDirectories = ConcurrentHashMap.newKeySet();
        long progressTimerId = -1;

        /** Creates counters for all normal bands plus the overflow band. */
        EventLoopScanState(int numberOfBands) {
            fileCountsPerBand = new AtomicLong[numberOfBands + 1];
            for (int bandIndex = 0; bandIndex <= numberOfBands; bandIndex++) {
                fileCountsPerBand[bandIndex] = new AtomicLong(0);
            }
        }

        /** Marks the scan as cancelled. */
        void requestCancel() {
            this.cancelled = true;
        }

        /** Returns whether cancellation has been requested. */
        boolean cancelled() {
            return cancelled;
        }
    }

    /** Starts an asynchronous filesystem scan using Vert.x callbacks. */
    public static FSReportJob getFSReport(String directory, long maximumFileSizeBytes, int numberOfBands, FSReportListener listener) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(listener, "listener");
        if (maximumFileSizeBytes < 0) {
            throw new IllegalArgumentException("maximumFileSizeBytes must be >= 0");
        }
        if (numberOfBands <= 0) {
            throw new IllegalArgumentException("numberOfBands must be > 0");
        }

        Vertx vertx = Vertx.vertx();
        FileSystem vertxFileSystem = vertx.fileSystem();
        EventLoopScanState scanState = new EventLoopScanState(numberOfBands);
        long scanStartTime = System.currentTimeMillis();

        // Publish progress snapshots on the event loop at a fixed interval.
        scanState.progressTimerId = vertx.setPeriodic(100, timerId -> {
            if (scanState.cancelled() || scanState.closed.get() || scanState.terminalEventEmitted.get()) {
                return;
            }
            listener.onUpdate(createReport(directory, maximumFileSizeBytes, numberOfBands, scanState, scanStartTime));
        });

        // Centralize final completion and Vert.x shutdown once all async tasks drain.
        Runnable finishScanIfIdle = () -> {
            if (scanState.pendingTaskCount.get() == 0 && scanState.closed.compareAndSet(false, true)) {
                if (scanState.progressTimerId != -1) {
                    vertx.cancelTimer(scanState.progressTimerId);
                }
                if (!scanState.cancelled() && scanState.terminalEventEmitted.compareAndSet(false, true)) {
                    listener.onCompleted(createReport(directory, maximumFileSizeBytes, numberOfBands, scanState, scanStartTime));
                }
                vertx.close();
            }
        };

        scanState.pendingTaskCount.incrementAndGet();
        vertx.runOnContext(ignored -> scanDirectory(
            directory,
            maximumFileSizeBytes,
            numberOfBands,
            vertxFileSystem,
            scanState,
            finishScanIfIdle,
            vertx,
            listener
        ));

        return new FSReportJob() {
            /** Requests cancellation and closes Vert.x once pending callbacks drain. */
            @Override
            public void cancel() {
                scanState.requestCancel();
                if (scanState.progressTimerId != -1) {
                    vertx.cancelTimer(scanState.progressTimerId);
                }
                if (scanState.pendingTaskCount.get() == 0 && scanState.closed.compareAndSet(false, true)) {
                    vertx.close();
                }
            }

            /** Reports whether this scan has been cancelled. */
            @Override
            public boolean isCancelled() {
                return scanState.cancelled();
            }
        };
    }

    /** Scans a directory with asynchronous Vert.x filesystem operations. */
    private static void scanDirectory(
        String path,
        long maximumFileSizeBytes,
        int numberOfBands,
        FileSystem vertxFileSystem,
        EventLoopScanState scanState,
        Runnable finishScanIfIdle,
        Vertx vertx,
        FSReportListener listener
    ) {
        if (scanState.cancelled()) {
            markTaskCompleted(scanState, finishScanIfIdle);
            return;
        }

        validateDirectoryPath(vertx, path, scanState).onComplete(validationResult -> {
            if (scanState.cancelled()) {
                markTaskCompleted(scanState, finishScanIfIdle);
                return;
            }

            if (validationResult.failed()) {
                // Only the root validation failure is reported as a terminal user error.
                if (scanState.pendingTaskCount.get() == 1) {
                    publishTerminalError(scanState, validationResult.cause(), listener, vertx);
                }
                markTaskCompleted(scanState, finishScanIfIdle);
                return;
            }

            PathValidationResult pathValidation = validationResult.result();
            if (pathValidation.status() == PathValidationStatus.DUPLICATE) {
                markTaskCompleted(scanState, finishScanIfIdle);
                return;
            }
            if (pathValidation.status() == PathValidationStatus.NOT_DIRECTORY) {
                // Non-root unreadable/non-directory children are skipped silently.
                if (scanState.pendingTaskCount.get() == 1) {
                    publishTerminalError(scanState, new IllegalArgumentException("Target directory is not a readable directory: " + path), listener, vertx);
                }
                markTaskCompleted(scanState, finishScanIfIdle);
                return;
            }

            vertxFileSystem.readDir(path).onComplete(directoryReadResult -> {
                if (scanState.cancelled()) {
                    markTaskCompleted(scanState, finishScanIfIdle);
                    return;
                }

                if (!directoryReadResult.succeeded()) {
                    if (scanState.pendingTaskCount.get() == 1) {
                        publishTerminalError(scanState, directoryReadResult.cause(), listener, vertx);
                    }
                    markTaskCompleted(scanState, finishScanIfIdle);
                    return;
                }

                // Each child stat is counted as one pending async task.
                for (String childPath : directoryReadResult.result()) {
                    scanState.pendingTaskCount.incrementAndGet();
                    vertxFileSystem.props(childPath).onComplete(filePropertiesResult -> {
                        if (scanState.cancelled()) {
                            markTaskCompleted(scanState, finishScanIfIdle);
                            return;
                        }

                        if (filePropertiesResult.succeeded()) {
                            FileProps fileProperties = filePropertiesResult.result();
                            if (fileProperties.isDirectory()) {
                                // Directory traversal itself becomes another tracked async task.
                                scanState.pendingTaskCount.incrementAndGet();
                                scanDirectory(
                                    childPath,
                                    maximumFileSizeBytes,
                                    numberOfBands,
                                    vertxFileSystem,
                                    scanState,
                                    finishScanIfIdle,
                                    vertx,
                                    listener
                                );
                            } else if (fileProperties.isRegularFile()) {
                                scanState.totalFileCount.incrementAndGet();
                                long fileSizeBytes = fileProperties.size();
                                int bandIndex = FSReport.getBandIndex(fileSizeBytes, maximumFileSizeBytes, numberOfBands);
                                scanState.fileCountsPerBand[bandIndex].incrementAndGet();
                            }
                        }

                        markTaskCompleted(scanState, finishScanIfIdle);
                    });
                }

                markTaskCompleted(scanState, finishScanIfIdle);
            });
        });
    }

    /** Validates a directory off the event loop and detects already visited canonical paths. */
    private static Future<PathValidationResult> validateDirectoryPath(Vertx vertx, String path, EventLoopScanState scanState) {
        return vertx.executeBlocking(() -> {
            File directoryFile = new File(path);
            String canonicalPath = directoryFile.getCanonicalPath();
            if (!directoryFile.exists() || !directoryFile.isDirectory()) {
                return new PathValidationResult(PathValidationStatus.NOT_DIRECTORY);
            }
            if (!scanState.visitedDirectories.add(canonicalPath)) {
                return new PathValidationResult(PathValidationStatus.DUPLICATE);
            }
            return new PathValidationResult(PathValidationStatus.VALID);
        });
    }

    /** Builds a report from the event-loop counters. */
    private static FSReport createReport(
        String directory,
        long maximumFileSizeBytes,
        int numberOfBands,
        EventLoopScanState scanState,
        long scanStartTime
    ) {
        long[] bands = new long[scanState.fileCountsPerBand.length];
        for (int bandIndex = 0; bandIndex < scanState.fileCountsPerBand.length; bandIndex++) {
            bands[bandIndex] = scanState.fileCountsPerBand[bandIndex].get();
        }
        return new FSReport(
            directory,
            maximumFileSizeBytes,
            numberOfBands,
            bands,
            scanState.totalFileCount.get(),
            System.currentTimeMillis() - scanStartTime
        );
    }

    /** Marks one async task done and finishes the scan when no tasks remain. */
    private static void markTaskCompleted(EventLoopScanState scanState, Runnable finishScanIfIdle) {
        if (scanState.pendingTaskCount.decrementAndGet() == 0) {
            finishScanIfIdle.run();
        }
    }

    /** Emits at most one terminal error and prevents completion from being published afterwards. */
    private static void publishTerminalError(
        EventLoopScanState scanState,
        Throwable error,
        FSReportListener listener,
        Vertx vertx
    ) {
        scanState.requestCancel();
        if (scanState.progressTimerId != -1) {
            vertx.cancelTimer(scanState.progressTimerId);
        }
        if (scanState.terminalEventEmitted.compareAndSet(false, true)) {
            listener.onError(error);
        }
    }
}
