package pcd.fsstat.paradigm.virtualthreads;

import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSReportJob;
import pcd.fsstat.common.FSReportListener;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Computes directory file statistics asynchronously using Java Virtual Threads. */
public class VirtualThreadsFSStat {

    /** Holds the cancellation flag shared by all virtual-thread tasks. */
    private static class VirtualThreadsScanState {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean terminalEventEmitted = new AtomicBoolean(false);
        final AtomicBoolean completionReleased = new AtomicBoolean(false);

        /** Marks the scan as cancelled. */
        void requestCancel() {
            cancelled.set(true);
        }

        /** Returns whether the scan has been cancelled. */
        boolean isCancelled() {
            return cancelled.get();
        }
    }

    /** Starts an asynchronous filesystem scan backed by virtual threads. */
    public static FSReportJob getFSReport(String directory, long maximumFileSizeBytes, int numberOfBands, FSReportListener listener) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(listener, "listener");
        if (maximumFileSizeBytes < 0) {
            throw new IllegalArgumentException("maximumFileSizeBytes must be >= 0");
        }
        if (numberOfBands <= 0) {
            throw new IllegalArgumentException("numberOfBands must be > 0");
        }

        // Initialize shared counters and lifecycle coordination.
        VirtualThreadsScanState scanState = new VirtualThreadsScanState();
        CountDownLatch completionSignal = new CountDownLatch(1);
        AtomicInteger activeTaskCount = new AtomicInteger(0);

        // LongAdder is used for thread-safe counting without contention.
        LongAdder totalFileCount = new LongAdder();
        LongAdder[] fileCountsPerBand = new LongAdder[numberOfBands + 1];
        for (int bandIndex = 0; bandIndex <= numberOfBands; bandIndex++) {
            fileCountsPerBand[bandIndex] = new LongAdder();
        }

        long scanStartTime = System.currentTimeMillis();
        Set<String> visitedDirectories = ConcurrentHashMap.newKeySet();

        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // Emit periodic snapshots while worker tasks are running.
        Thread progressReporterThread = Thread.ofVirtual().start(() -> {
            try {
                while (!scanState.isCancelled() && !scanState.terminalEventEmitted.get() && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100);
                    if (!scanState.isCancelled() && !scanState.terminalEventEmitted.get()) {
                        listener.onUpdate(createReport(
                            directory,
                            maximumFileSizeBytes,
                            numberOfBands,
                            fileCountsPerBand,
                            totalFileCount,
                            scanStartTime
                        ));
                    }
                }
            } catch (InterruptedException ignored) {
            }
        });

        // Validate the root directory, start traversal, then publish the final report.
        Thread.ofVirtual().start(() -> {
            try {
                File rootDirectory = new File(directory);
                if (!rootDirectory.exists() || !rootDirectory.isDirectory()) {
                    throw new IllegalArgumentException("Target is not a valid directory: " + directory);
                }

                // Submit the root as the first counted task.
                submitDirectoryTask(
                    rootDirectory,
                    true,
                    maximumFileSizeBytes,
                    numberOfBands,
                    virtualThreadExecutor,
                    activeTaskCount,
                    totalFileCount,
                    fileCountsPerBand,
                    scanState,
                    completionSignal,
                    visitedDirectories,
                    progressReporterThread,
                    listener
                );

                completionSignal.await();
                progressReporterThread.interrupt();
                virtualThreadExecutor.shutdown();

                if (!scanState.isCancelled() && scanState.terminalEventEmitted.compareAndSet(false, true)) {
                    listener.onCompleted(createReport(
                        directory,
                        maximumFileSizeBytes,
                        numberOfBands,
                        fileCountsPerBand,
                        totalFileCount,
                        scanStartTime
                    ));
                }
            } catch (Throwable t) {
                publishTerminalError(scanState, completionSignal, progressReporterThread, virtualThreadExecutor, listener, t);
            }
        });

        return new FSReportJob() {
            /** Requests cancellation and interrupts active scan infrastructure. */
            @Override
            public void cancel() {
                scanState.requestCancel();
                progressReporterThread.interrupt();
                virtualThreadExecutor.shutdownNow();
                releaseCompletion(completionSignal, scanState);
            }

            /** Reports whether this scan has been cancelled. */
            @Override
            public boolean isCancelled() {
                return scanState.isCancelled();
            }
        };
    }

    /** Recursively scans a directory and submits subdirectories as separate virtual-thread tasks. */
    private static void scanDirectoryRecursively(
        File currentDirectory,
        long maximumFileSizeBytes,
        int numberOfBands,
        ExecutorService virtualThreadExecutor,
        AtomicInteger activeTaskCount,
        LongAdder totalFileCount,
        LongAdder[] fileCountsPerBand,
        VirtualThreadsScanState scanState,
        CountDownLatch completionSignal,
        Set<String> visitedDirectories
    ) {
        if (scanState.isCancelled()) {
            return;
        }

        // Canonical paths prevent revisiting symlink cycles.
        try {
            String canonicalPath = currentDirectory.getCanonicalPath();
            if (!visitedDirectories.add(canonicalPath)) {
                return;
            }
        } catch (IOException ignored) {
            return;
        }

        File[] children = currentDirectory.listFiles();
        if (children == null) {
            return;
        }

        // Count files immediately and fan out directories as independent tasks.
        for (File child : children) {
            if (scanState.isCancelled()) {
                return;
            }
            if (child.isDirectory()) {
                submitChildDirectoryTask(
                    child,
                    maximumFileSizeBytes,
                    numberOfBands,
                    virtualThreadExecutor,
                    activeTaskCount,
                    totalFileCount,
                    fileCountsPerBand,
                    scanState,
                    completionSignal,
                    visitedDirectories
                );
            } else if (child.isFile()) {
                totalFileCount.increment();
                long fileSizeBytes = child.length();
                int bandIndex = FSReport.getBandIndex(fileSizeBytes, maximumFileSizeBytes, numberOfBands);
                fileCountsPerBand[bandIndex].increment();
            }
        }
    }

    /** Builds a report from the virtual-thread counters. */
    private static FSReport createReport(
        String directory,
        long maximumFileSizeBytes,
        int numberOfBands,
        LongAdder[] fileCountsPerBand,
        LongAdder totalFileCount,
        long scanStartTime
    ) {
        long[] bands = new long[fileCountsPerBand.length];
        for (int bandIndex = 0; bandIndex < fileCountsPerBand.length; bandIndex++) {
            bands[bandIndex] = fileCountsPerBand[bandIndex].sum();
        }
        return new FSReport(
            directory,
            maximumFileSizeBytes,
            numberOfBands,
            bands,
            totalFileCount.sum(),
            System.currentTimeMillis() - scanStartTime
        );
    }

    /** Decrements the active-task counter and releases the waiter when the scan is idle. */
    private static void markTaskCompleted(AtomicInteger activeTaskCount, CountDownLatch completionSignal) {
        if (activeTaskCount.decrementAndGet() == 0) {
            completionSignal.countDown();
        }
    }

    /** Submits the root directory task and treats submission failures as terminal errors. */
    private static void submitDirectoryTask(
        File directory,
        boolean rootTask,
        long maximumFileSizeBytes,
        int numberOfBands,
        ExecutorService virtualThreadExecutor,
        AtomicInteger activeTaskCount,
        LongAdder totalFileCount,
        LongAdder[] fileCountsPerBand,
        VirtualThreadsScanState scanState,
        CountDownLatch completionSignal,
        Set<String> visitedDirectories,
        Thread progressReporterThread,
        FSReportListener listener
    ) {
        activeTaskCount.incrementAndGet();
        try {
            virtualThreadExecutor.submit(() -> {
                try {
                    scanDirectoryRecursively(
                        directory,
                        maximumFileSizeBytes,
                        numberOfBands,
                        virtualThreadExecutor,
                        activeTaskCount,
                        totalFileCount,
                        fileCountsPerBand,
                        scanState,
                        completionSignal,
                        visitedDirectories
                    );
                    if (rootTask && !scanState.isCancelled() && directory.listFiles() == null) {
                        throw new IOException("Target directory is not readable: " + directory.getPath());
                    }
                } catch (Throwable error) {
                    publishTerminalError(scanState, completionSignal, progressReporterThread, virtualThreadExecutor, listener, error);
                } finally {
                    markTaskCompleted(activeTaskCount, completionSignal);
                }
            });
        } catch (RejectedExecutionException error) {
            markTaskCompleted(activeTaskCount, completionSignal);
            if (!scanState.isCancelled()) {
                publishTerminalError(scanState, completionSignal, progressReporterThread, virtualThreadExecutor, listener, error);
            }
        }
    }

    /** Submits child directory work while keeping task accounting coherent during cancellation races. */
    private static void submitChildDirectoryTask(
        File directory,
        long maximumFileSizeBytes,
        int numberOfBands,
        ExecutorService virtualThreadExecutor,
        AtomicInteger activeTaskCount,
        LongAdder totalFileCount,
        LongAdder[] fileCountsPerBand,
        VirtualThreadsScanState scanState,
        CountDownLatch completionSignal,
        Set<String> visitedDirectories
    ) {
        activeTaskCount.incrementAndGet();
        try {
            virtualThreadExecutor.submit(() -> {
                try {
                    scanDirectoryRecursively(
                        directory,
                        maximumFileSizeBytes,
                        numberOfBands,
                        virtualThreadExecutor,
                        activeTaskCount,
                        totalFileCount,
                        fileCountsPerBand,
                        scanState,
                        completionSignal,
                        visitedDirectories
                    );
                } finally {
                    markTaskCompleted(activeTaskCount, completionSignal);
                }
            });
        } catch (RejectedExecutionException ignored) {
            markTaskCompleted(activeTaskCount, completionSignal);
        }
    }

    /** Emits a single terminal error, then stops the active infrastructure. */
    private static void publishTerminalError(
        VirtualThreadsScanState scanState,
        CountDownLatch completionSignal,
        Thread progressReporterThread,
        ExecutorService virtualThreadExecutor,
        FSReportListener listener,
        Throwable error
    ) {
        scanState.requestCancel();
        progressReporterThread.interrupt();
        virtualThreadExecutor.shutdownNow();
        releaseCompletion(completionSignal, scanState);
        if (scanState.terminalEventEmitted.compareAndSet(false, true)) {
            listener.onError(error);
        }
    }

    /** Releases the completion waiter exactly once. */
    private static void releaseCompletion(CountDownLatch completionSignal, VirtualThreadsScanState scanState) {
        if (scanState.completionReleased.compareAndSet(false, true)) {
            completionSignal.countDown();
        }
    }
}
