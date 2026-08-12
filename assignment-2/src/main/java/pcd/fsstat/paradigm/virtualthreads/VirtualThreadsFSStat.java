package pcd.fsstat.paradigm.virtualthreads;

import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSReportJob;
import pcd.fsstat.common.FSReportListener;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Computes directory file statistics asynchronously using Java Virtual Threads. */
public class VirtualThreadsFSStat {

    /** Holds the cancellation flag shared by all virtual-thread tasks. */
    private static class VirtualThreadsScanState {
        volatile boolean isCancelled = false;
    }

    /** Starts an asynchronous filesystem scan backed by virtual threads. */
    public static FSReportJob getFSReport(String directory, long maxFS, int nb, FSReportListener listener) {
        // Initialize shared counters and lifecycle coordination.
        VirtualThreadsScanState scanState = new VirtualThreadsScanState();
        CountDownLatch completionSignal = new CountDownLatch(1);
        AtomicInteger activeTaskCount = new AtomicInteger(0);

        // LongAdder is used for thread-safe counting without contention.
        LongAdder totalFileCount = new LongAdder();
        LongAdder[] fileCountsPerBand = new LongAdder[nb + 1];
        for (int bandIndex = 0; bandIndex <= nb; bandIndex++) {
            fileCountsPerBand[bandIndex] = new LongAdder();
        }

        long scanStartTime = System.currentTimeMillis();
        Set<String> visitedDirectories = ConcurrentHashMap.newKeySet();

        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        // Emit periodic snapshots while worker tasks are running.
        Thread progressReporterThread = Thread.ofVirtual().start(() -> {
            try {
                while (!scanState.isCancelled && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100);
                    listener.onUpdate(createReport(directory, maxFS, nb, fileCountsPerBand, totalFileCount, scanStartTime));
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
                activeTaskCount.incrementAndGet();
                virtualThreadExecutor.submit(() -> {
                    try {
                        scanDirectoryRecursively(
                            rootDirectory,
                            maxFS,
                            nb,
                            virtualThreadExecutor,
                            activeTaskCount,
                            totalFileCount,
                            fileCountsPerBand,
                            scanState,
                            completionSignal,
                            visitedDirectories
                        );
                    } catch (Exception e) {
                        listener.onError(e);
                        progressReporterThread.interrupt();
                        virtualThreadExecutor.shutdownNow();
                        completionSignal.countDown();
                    } finally {
                        markTaskCompleted(activeTaskCount, completionSignal);
                    }
                });

                completionSignal.await();
                progressReporterThread.interrupt();
                virtualThreadExecutor.shutdown();

                if (!scanState.isCancelled) {
                    listener.onCompleted(createReport(directory, maxFS, nb, fileCountsPerBand, totalFileCount, scanStartTime));
                }
            } catch (Throwable t) {
                listener.onError(t);
                progressReporterThread.interrupt();
                virtualThreadExecutor.shutdownNow();
            }
        });

        return new FSReportJob() {
            /** Requests cancellation and interrupts active scan infrastructure. */
            @Override
            public void cancel() {
                scanState.isCancelled = true;
                progressReporterThread.interrupt();
                virtualThreadExecutor.shutdownNow();
                completionSignal.countDown();
            }

            /** Reports whether this scan has been cancelled. */
            @Override
            public boolean isCancelled() {
                return scanState.isCancelled;
            }
        };
    }

    /** Recursively scans a directory and submits subdirectories as separate virtual-thread tasks. */
    private static void scanDirectoryRecursively(
        File currentDirectory,
        long maxFS,
        int nb,
        ExecutorService virtualThreadExecutor,
        AtomicInteger activeTaskCount,
        LongAdder totalFileCount,
        LongAdder[] fileCountsPerBand,
        VirtualThreadsScanState scanState,
        CountDownLatch completionSignal,
        Set<String> visitedDirectories
    ) {
        if (scanState.isCancelled) {
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
            if (scanState.isCancelled) {
                return;
            }
            if (child.isDirectory()) {
                activeTaskCount.incrementAndGet();
                virtualThreadExecutor.submit(() -> {
                    try {
                        scanDirectoryRecursively(
                            child,
                            maxFS,
                            nb,
                            virtualThreadExecutor,
                            activeTaskCount,
                            totalFileCount,
                            fileCountsPerBand,
                            scanState,
                            completionSignal,
                            visitedDirectories
                        );
                    } catch (Exception ignored) {
                    } finally {
                        markTaskCompleted(activeTaskCount, completionSignal);
                    }
                });
            } else if (child.isFile()) {
                totalFileCount.increment();
                long fileSizeBytes = child.length();
                int bandIndex = FSReport.getBandIndex(fileSizeBytes, maxFS, nb);
                fileCountsPerBand[bandIndex].increment();
            }
        }
    }

    /** Builds a report from the virtual-thread counters. */
    private static FSReport createReport(
        String directory,
        long maxFS,
        int nb,
        LongAdder[] fileCountsPerBand,
        LongAdder totalFileCount,
        long scanStartTime
    ) {
        long[] bands = new long[fileCountsPerBand.length];
        for (int bandIndex = 0; bandIndex < fileCountsPerBand.length; bandIndex++) {
            bands[bandIndex] = fileCountsPerBand[bandIndex].sum();
        }
        return new FSReport(directory, maxFS, nb, bands, totalFileCount.sum(), System.currentTimeMillis() - scanStartTime);
    }

    /** Decrements the active-task counter and releases the waiter when the scan is idle. */
    private static void markTaskCompleted(AtomicInteger activeTaskCount, CountDownLatch completionSignal) {
        if (activeTaskCount.decrementAndGet() == 0) {
            completionSignal.countDown();
        }
    }
}
