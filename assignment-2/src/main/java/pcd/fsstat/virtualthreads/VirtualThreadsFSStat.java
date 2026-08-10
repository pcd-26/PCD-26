package pcd.fsstat.virtualthreads;

import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSReportJob;
import pcd.fsstat.common.FSReportListener;
import pcd.fsstat.common.FSUtils;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Computes directory file statistics asynchronously using Java Virtual Threads. */
public class VirtualThreadsFSStat {

    private static class VirtualThreadsScanState {
        volatile boolean isCancelled = false;
    }

    public static FSReportJob getFSReport(String directory, long maxFS, int nb, FSReportListener listener) {
        VirtualThreadsScanState scanState = new VirtualThreadsScanState();
        CountDownLatch completionSignal = new CountDownLatch(1);
        AtomicInteger activeTaskCount = new AtomicInteger(0);

        LongAdder totalFileCount = new LongAdder();
        LongAdder[] fileCountsPerBand = new LongAdder[nb + 1];
        for (int bandIndex = 0; bandIndex <= nb; bandIndex++) {
            fileCountsPerBand[bandIndex] = new LongAdder();
        }

        long scanStartTime = System.currentTimeMillis();
        java.util.Set<String> visitedDirectories = java.util.concurrent.ConcurrentHashMap.newKeySet();

        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        Thread progressReporterThread = Thread.ofVirtual().start(() -> {
            try {
                while (!scanState.isCancelled && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100);
                    listener.onUpdate(FSUtils.createReport(directory, maxFS, nb, fileCountsPerBand, totalFileCount, scanStartTime));
                }
            } catch (InterruptedException ignored) {
            }
        });

        Thread.ofVirtual().start(() -> {
            try {
                File rootDirectory = new File(directory);
                if (!rootDirectory.exists() || !rootDirectory.isDirectory()) {
                    throw new IllegalArgumentException("Target is not a valid directory: " + directory);
                }

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
                    listener.onCompleted(FSUtils.createReport(directory, maxFS, nb, fileCountsPerBand, totalFileCount, scanStartTime));
                }
            } catch (Throwable t) {
                listener.onError(t);
                progressReporterThread.interrupt();
                virtualThreadExecutor.shutdownNow();
            }
        });

        return new FSReportJob() {
            @Override
            public void cancel() {
                scanState.isCancelled = true;
                progressReporterThread.interrupt();
                virtualThreadExecutor.shutdownNow();
                completionSignal.countDown();
            }

            @Override
            public boolean isCancelled() {
                return scanState.isCancelled;
            }
        };
    }

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
        java.util.Set<String> visitedDirectories
    ) {
        if (scanState.isCancelled) {
            return;
        }

        try {
            String canonicalPath = currentDirectory.getCanonicalPath();
            if (!visitedDirectories.add(canonicalPath)) {
                return;
            }
        } catch (java.io.IOException ignored) {
            return;
        }

        File[] children = currentDirectory.listFiles();
        if (children == null) {
            return;
        }

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

    private static void markTaskCompleted(AtomicInteger activeTaskCount, CountDownLatch completionSignal) {
        if (activeTaskCount.decrementAndGet() == 0) {
            completionSignal.countDown();
        }
    }
}
