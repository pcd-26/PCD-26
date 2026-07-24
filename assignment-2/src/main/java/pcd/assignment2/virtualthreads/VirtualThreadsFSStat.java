package pcd.assignment2.virtualthreads;

import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportJob;
import pcd.assignment2.common.FSReportListener;
import pcd.assignment2.common.FSUtils;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Computes directory file statistics asynchronously using Java Virtual Threads. */
public class VirtualThreadsFSStat {

    private static class ScanState {
        volatile boolean cancelled = false;
    }

    public static FSReportJob getFSReport(String directory, long maxFS, int nb, FSReportListener listener) {
        ScanState state = new ScanState();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger runningTasks = new AtomicInteger(0);

        LongAdder totalFiles = new LongAdder();
        LongAdder[] bandsCount = new LongAdder[nb + 1];
        for (int i = 0; i <= nb; i++) {
            bandsCount[i] = new LongAdder();
        }

        long startTime = System.currentTimeMillis();
        java.util.Set<String> seenDirectories = java.util.concurrent.ConcurrentHashMap.newKeySet();

        ExecutorService taskExecutor = Executors.newVirtualThreadPerTaskExecutor();

        Thread reporterThread = Thread.ofVirtual().start(() -> {
            try {
                while (!state.cancelled && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100);
                    listener.onUpdate(FSUtils.createReport(directory, maxFS, nb, bandsCount, totalFiles, startTime));
                }
            } catch (InterruptedException ignored) {
            }
        });

        Thread.ofVirtual().start(() -> {
            try {
                File rootDir = new File(directory);
                if (!rootDir.exists() || !rootDir.isDirectory()) {
                    throw new IllegalArgumentException("Target is not a valid directory: " + directory);
                }

                runningTasks.incrementAndGet();
                taskExecutor.submit(() -> {
                    try {
                        walkDirectory(rootDir, maxFS, nb, taskExecutor, runningTasks, totalFiles, bandsCount, state, finished, seenDirectories);
                    } catch (Exception e) {
                        listener.onError(e);
                        reporterThread.interrupt();
                        taskExecutor.shutdownNow();
                        finished.countDown();
                    } finally {
                        completeTask(runningTasks, finished);
                    }
                });

                finished.await();
                reporterThread.interrupt();
                taskExecutor.shutdown();

                if (!state.cancelled) {
                    listener.onCompleted(FSUtils.createReport(directory, maxFS, nb, bandsCount, totalFiles, startTime));
                }
            } catch (Throwable t) {
                listener.onError(t);
                reporterThread.interrupt();
                taskExecutor.shutdownNow();
            }
        });

        return new FSReportJob() {
            @Override
            public void cancel() {
                state.cancelled = true;
                reporterThread.interrupt();
                taskExecutor.shutdownNow();
                finished.countDown();
            }

            @Override
            public boolean isCancelled() {
                return state.cancelled;
            }
        };
    }

    private static void walkDirectory(
        File dir,
        long maxFS,
        int nb,
        ExecutorService taskExecutor,
        AtomicInteger runningTasks,
        LongAdder totalFiles,
        LongAdder[] bandsCount,
        ScanState state,
        CountDownLatch finished,
        java.util.Set<String> seenDirectories
    ) {
        if (state.cancelled) {
            return;
        }

        try {
            String canonicalPath = dir.getCanonicalPath();
            if (!seenDirectories.add(canonicalPath)) {
                return;
            }
        } catch (java.io.IOException ignored) {
            return;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (state.cancelled) {
                return;
            }
            if (file.isDirectory()) {
                runningTasks.incrementAndGet();
                taskExecutor.submit(() -> {
                    try {
                        walkDirectory(file, maxFS, nb, taskExecutor, runningTasks, totalFiles, bandsCount, state, finished, seenDirectories);
                    } catch (Exception ignored) {
                    } finally {
                        completeTask(runningTasks, finished);
                    }
                });
            } else if (file.isFile()) {
                totalFiles.increment();
                long size = file.length();
                int idx = FSReport.getBandIndex(size, maxFS, nb);
                bandsCount[idx].increment();
            }
        }
    }

    private static void completeTask(AtomicInteger runningTasks, CountDownLatch finished) {
        if (runningTasks.decrementAndGet() == 0) {
            finished.countDown();
        }
    }
}
