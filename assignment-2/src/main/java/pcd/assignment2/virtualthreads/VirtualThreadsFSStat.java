package pcd.assignment2.virtualthreads;

import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportJob;
import pcd.assignment2.common.FSReportListener;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;

/**
 * Computes directory file statistics asynchronously using Java Virtual Threads.
 *
 * <p><strong>Shared State & Synchronization Strategy:</strong>
 * <ul>
 *   <li>The shared counters (total files count, and size bands distribution) are owned by the coordinator task
 *       and mutated concurrently by recursively spawned sub-directory scanning tasks.</li>
 *   <li>To avoid lock contention, we use lock-free {@link LongAdder} objects to aggregate the statistics.</li>
 *   <li>Task completion is coordinated via a combination of a thread-safe {@link AtomicInteger} (tracking active tasks)
 *       and a {@link CountDownLatch} (enabling the coordinator to block-wait for completion).</li>
 *   <li>Cancellation is handled using a {@code volatile boolean} flag, checking it before spawning tasks
 *       or performing file walks, combined with an abrupt shutdown of the virtual thread {@link ExecutorService}.</li>
 * </ul>
 */
public class VirtualThreadsFSStat {

    /**
     * Holds the shared job execution state, including cancellation flags.
     */
    private static class JobState {
        /**
         * Volatile boolean checked by all scanning threads to abort processing quickly upon cancellation.
         */
        volatile boolean cancelled = false;
    }

    /**
     * Starts an asynchronous filesystem statistics scan using virtual threads.
     *
     * @param directory The root directory path to scan.
     * @param maxFS     The maximum file size threshold.
     * @param nb        The number of file size bands.
     * @param listener  The listener to notify of intermediate progress, completion, or errors.
     * @return An FSReportJob instance to query cancel status or halt execution.
     */
    public static FSReportJob getFSReport(String directory, long maxFS, int nb, FSReportListener listener) {
        JobState state = new JobState();
        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicInteger activeTasks = new AtomicInteger(0);

        LongAdder totalFiles = new LongAdder();
        LongAdder[] bandsCount = new LongAdder[nb + 1];
        for (int i = 0; i <= nb; i++) {
            bandsCount[i] = new LongAdder();
        }

        long startTime = System.currentTimeMillis();
        Set<String> visitedPaths = ConcurrentHashMap.newKeySet();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Updater thread for periodic updates
        Thread updaterThread = Thread.ofVirtual().start(() -> {
            try {
                while (!state.cancelled && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100);
                    long[] currentBands = new long[nb + 1];
                    for (int i = 0; i <= nb; i++) {
                        currentBands[i] = bandsCount[i].sum();
                    }
                    FSReport partialReport = new FSReport(
                        directory,
                        maxFS,
                        nb,
                        currentBands,
                        totalFiles.sum(),
                        System.currentTimeMillis() - startTime
                    );
                    listener.onUpdate(partialReport);
                }
            } catch (InterruptedException e) {
                // Terminate updater loop
            }
        });

        // Coordinator thread that launches and awaits the scan
        Thread.ofVirtual().start(() -> {
            try {
                File rootDir = new File(directory);
                if (!rootDir.exists() || !rootDir.isDirectory()) {
                    throw new IllegalArgumentException("Target is not a valid directory: " + directory);
                }

                activeTasks.incrementAndGet();
                executor.submit(() -> {
                    try {
                        scanDirectory(rootDir, maxFS, nb, executor, activeTasks, totalFiles, bandsCount, state, completionLatch, visitedPaths);
                    } catch (Exception e) {
                        listener.onError(e);
                        updaterThread.interrupt();
                        executor.shutdownNow();
                        completionLatch.countDown();
                    } finally {
                        decrementAndCheck(activeTasks, completionLatch);
                    }
                });

                // Wait for all tasks to complete or job to be cancelled
                completionLatch.await();
                updaterThread.interrupt();
                executor.shutdown();

                if (!state.cancelled) {
                    long[] finalBands = new long[nb + 1];
                    for (int i = 0; i <= nb; i++) {
                        finalBands[i] = bandsCount[i].sum();
                    }
                    FSReport finalReport = new FSReport(
                        directory,
                        maxFS,
                        nb,
                        finalBands,
                        totalFiles.sum(),
                        System.currentTimeMillis() - startTime
                    );
                    listener.onCompleted(finalReport);
                }
            } catch (Throwable t) {
                listener.onError(t);
                updaterThread.interrupt();
                executor.shutdownNow();
            }
        });

        return new FSReportJob() {
            @Override
            public void cancel() {
                state.cancelled = true;
                updaterThread.interrupt();
                executor.shutdownNow();
                completionLatch.countDown();
            }

            @Override
            public boolean isCancelled() {
                return state.cancelled;
            }
        };
    }

    /**
     * Recursively walks a directory, submitting sub-directory scanning tasks to the virtual executor,
     * and incrementing the size distribution counters for any regular files found.
     *
     * @param dir          The directory to scan.
     * @param maxFS        The maximum file size threshold.
     * @param nb           The number of size bands.
     * @param executor     The ExecutorService executing virtual threads.
     * @param activeTasks  Counter of active scanning tasks.
     * @param totalFiles   Accumulator for total file count.
     * @param bandsCount   Accumulators for size bands distribution.
     * @param state        The shared job cancellation state.
     * @param latch        The latch to trigger completion when activeTasks drops to zero.
     * @param visitedPaths Thread-safe set tracking already visited directory paths to avoid symlink loops.
     */
    private static void scanDirectory(
        File dir,
        long maxFS,
        int nb,
        ExecutorService executor,
        AtomicInteger activeTasks,
        LongAdder totalFiles,
        LongAdder[] bandsCount,
        JobState state,
        CountDownLatch latch,
        Set<String> visitedPaths
    ) {
        if (state.cancelled) {
            return;
        }

        try {
            String canonicalPath = dir.getCanonicalPath();
            if (!visitedPaths.add(canonicalPath)) {
                return; // Cycle detected: skip this directory
            }
        } catch (IOException e) {
            return; // Skip on access/resolution failure
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
                activeTasks.incrementAndGet();
                executor.submit(() -> {
                    try {
                        scanDirectory(file, maxFS, nb, executor, activeTasks, totalFiles, bandsCount, state, latch, visitedPaths);
                    } catch (Exception e) {
                        // Continue on subdirectory access errors
                    } finally {
                        decrementAndCheck(activeTasks, latch);
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

    /**
     * Decrements the active tasks counter and count downs the latch if no tasks remain active.
     *
     * @param activeTasks Counter of active scanning tasks.
     * @param latch       The completion synchronization latch.
     */
    private static void decrementAndCheck(AtomicInteger activeTasks, CountDownLatch latch) {
        if (activeTasks.decrementAndGet() == 0) {
            latch.countDown();
        }
    }
}
